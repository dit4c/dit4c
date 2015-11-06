package utils

import play.api.libs.iteratee._
import scala.concurrent.{ExecutionContext, Future, Promise}
import play.api.libs.iteratee.{Cont, Error}
import scala.util.{Try, Failure, Success}
import akka.agent.Agent
import org.mapdb.DBMaker
import org.mapdb.Serializer
import java.io._
import scala.util.Random
import java.util.concurrent.Executors

object Buffering {

  implicit val ec = ExecutionContext.fromExecutorService(
      Executors.newCachedThreadPool)

  def diskBuffer(e: Enumerator[Array[Byte]]): Enumerator[Array[Byte]] = {
    val buffer = new Buffer()
    (e run Iteratee.foreach(buffer.enqueue))
      .foreach(_ => buffer.finish)
    Enumerator.generateM(buffer.dequeue).onDoneEnumerating(buffer.close)
  }


  class Buffer {

    val db = DBMaker.newTempFileDB
      .asyncWriteEnable
      .transactionDisable
      .deleteFilesAfterClose
      .closeOnJvmShutdown
      .make
    val queue = db.createQueue("buffer", Serializer.BYTE_ARRAY, false)
    val pending = scala.collection.mutable.Queue.empty[Promise[Option[Array[Byte]]]]
    var isDone = false

    def enqueue(v: Array[Byte]) =
      this.synchronized {
        pending.dequeueFirst(_ => true) match {
          case Some(p) => p.success(Some(v))
          case None => addToQueue(v)
        }
      }

    def dequeue: Future[Option[Array[Byte]]] =
      this.synchronized {
        takeFromQueue match {
          case Some(v) => Future.successful(Some(v))
          case None if isDone => Future.successful(None)
          case None if pending.isEmpty =>
            val p = Promise[Option[Array[Byte]]]()
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

    private def addToQueue(v: Array[Byte]) { if (!db.isClosed) queue.add(v) }
    private def takeFromQueue: Option[Array[Byte]] = Option(queue.poll)
  }

}