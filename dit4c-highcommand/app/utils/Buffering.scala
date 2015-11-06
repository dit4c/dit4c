package utils

import scala.concurrent.ExecutionContext
import play.api.libs.iteratee._
import scala.concurrent.{ExecutionContext, Future, Promise}
import play.api.libs.iteratee.{Cont, Error}
import scala.util.{Try, Failure, Success}
import akka.agent.Agent
import akka.util.ByteString
import org.mapdb.DBMaker
import org.mapdb.Serializer
import java.io._
import scala.util.Random

object Buffering {
  def diskBuffer(e: Enumerator[ByteString])(implicit ec: ExecutionContext): Enumerator[ByteString] = {
    val buffer = new Buffer()
    (e run Iteratee.foreach(buffer.enqueue)).foreach(_ => buffer.finish)
    Enumerator.generateM(buffer.dequeue).onDoneEnumerating(buffer.close)
  }


  class Buffer {

    val db = DBMaker.newTempFileDB
      .asyncWriteEnable
      .transactionDisable
      .deleteFilesAfterClose
      .closeOnJvmShutdown
      .make
    val queue = db.createQueue("buffer", ByteStringSerializer, false)
    val pending = scala.collection.mutable.Queue.empty[Promise[Option[ByteString]]]
    var isDone = false

    def enqueue(v: ByteString) =
      this.synchronized {
        pending.dequeueFirst(_ => true) match {
          case Some(p) => p.success(Some(v))
          case None => addToQueue(v)
        }
      }

    def dequeue: Future[Option[ByteString]] =
      this.synchronized {
        takeFromQueue match {
          case Some(v) => Future.successful(Some(v))
          case None if isDone => Future.successful(None)
          case None if pending.isEmpty =>
            val p = Promise[Option[ByteString]]()
            pending.enqueue(p)
            p.future
          case e =>
            println(e)
            throw new RuntimeException("Unhandled state")
        }
      }

    def finish = this.synchronized {
      isDone = true
      pending.foreach(_.success(takeFromQueue))
    }

    def close = db.close

    private def addToQueue(v: ByteString) { queue.add(v) }
    private def takeFromQueue: Option[ByteString] = Option(queue.poll)
  }

  object ByteStringSerializer extends Serializer[ByteString] with Serializable {
    val internal = Serializer.BYTE_ARRAY

    override def deserialize(in: DataInput, available: Int) =
      ByteString(internal.deserialize(in, available))

    override def serialize(out: DataOutput, value: ByteString) =
      internal.serialize(out, value.toArray)

    override def fixedSize = internal.fixedSize


  }

}