package dit4c.machineshop.docker.utils

import akka.stream.stage._
import akka.util.ByteString
import java.io._
import org.mapdb._
import java.util.concurrent.BlockingQueue

class DiskBasedChunker(val chunkSize: Int)
    extends DetachedStage[ByteString, ByteString] {

  var db: DB = null
  var queue: BlockingQueue[ByteString] = null
  var next: ByteString = ByteString.empty

  override def preStart(ctx: LifecycleContext) {
    db = DBMaker.newTempFileDB
      .asyncWriteEnable
      .transactionDisable
      .deleteFilesAfterClose
      .closeOnJvmShutdown
      .make
    queue = db.createQueue("buffer", ByteStringSerializer, false)
  }

  /**
   * Fill to chunk size from the queue, then send what you have.
   */
  def onPull(ctx: DetachedContext[ByteString]) = {
    import scala.util.control.Breaks._
    val emit = nextElement
    if (!emit.isEmpty) ctx.push(emit)
    else if (!ctx.isFinishing) ctx.holdDownstream
    else ctx.finish
  }

  /**
   * Take it all, with no back-pressure.
   */
  override def onPush(elem: ByteString, ctx: DetachedContext[ByteString]) = {
    if (elem.size <= chunkSize) queue.add(elem)
    else elem.grouped(chunkSize).foreach(queue.add)
    if (ctx.isHoldingDownstream) ctx.pushAndPull(nextElement)
    else ctx.pull
  }

  override def onUpstreamFinish(ctx: DetachedContext[ByteString]) = {
    ctx.absorbTermination
  }

  override def postStop {
    queue = null
    db.close
    db = null
  }

  def nextElement: ByteString = {
    var (out, over) = (next, ByteString.empty)
    while (queue.size > 0 && over.isEmpty) {
      val headE = queue.peek
      if ((out.size + headE.size) <= chunkSize) {
        out ++= headE
      } else {
        val neededBytes = chunkSize - out.size;
        out = out ++ headE.take(neededBytes)
        over = headE.drop(neededBytes)
      }
      queue.remove()
    }
    next = over
    out
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