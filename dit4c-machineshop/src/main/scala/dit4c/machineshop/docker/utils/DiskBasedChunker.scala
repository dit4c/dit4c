package dit4c.machineshop.docker.utils

import akka.stream.stage._
import akka.util.ByteString
import com.squareup.tape._
import java.io.File
import java.io.ByteArrayOutputStream

class DiskBasedChunker(val chunkSize: Int)
    extends DetachedStage[ByteString, ByteString] {

  var queueFile: File = null
  var queue: ObjectQueue[ByteString] = null
  var next: ByteString = ByteString.empty

  override def preStart(ctx: LifecycleContext) {
    queueFile = File.createTempFile(this.getClass.getSimpleName, "")
    queueFile.delete // FileObjectQueue expects to create the file
    queue = new FileObjectQueue(queueFile, ByteStringConverter)
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
    elem.grouped(chunkSize).foreach(queue.add)
    if (ctx.isHoldingDownstream) ctx.pushAndPull(nextElement)
    else ctx.pull
  }

  override def onUpstreamFinish(ctx: DetachedContext[ByteString]) = {
    ctx.absorbTermination
  }

  override def postStop {
    queue = null
    queueFile.delete
  }

  def nextElement: ByteString = {
    var (out, over) = (next, ByteString.empty)
    while (queue.size > 0 && over.isEmpty) {
      val headE = queue.peek
      if ((out.size + headE.size) < chunkSize) {
        out ++= headE
      } else {
        val neededBytes = chunkSize - out.size;
        out = out ++ headE.take(neededBytes)
        over = headE.drop(neededBytes)
      }
      queue.remove
    }
    next = over
    out
  }

  object ByteStringConverter extends FileObjectQueue.Converter[ByteString] {
    override def from(bytes: Array[Byte]) = ByteString(bytes)
    def toStream(v: akka.util.ByteString, os: java.io.OutputStream) =
      os.write(v.toArray)
  }
}