package models

import play.api.libs.iteratee.Enumerator
import play.api.libs.json._
import providers.db.CouchDB
import com.google.inject.Inject
import com.google.inject.Singleton
import play.api.libs.iteratee.Concurrent
import akka.stream.scaladsl.Source
import rx.RxReactiveStreams
import rx.lang.scala.JavaConversions
import play.api.libs.streams.Streams
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ChangeFeed @Inject() @Singleton() (
    db: CouchDB.Database)(implicit ec: ExecutionContext) {
  import ChangeFeed._

  def changes[T](updateSeqNum: Option[Int])(implicit rjs: Reads[T]): Source[Change[T], Future[Unit]] = {
    import net.liftweb.json._
    val changeStream = db.asSohvaDb.changes(updateSeqNum, None)
    val publisher = RxReactiveStreams.toPublisher(
        JavaConversions.toJavaObservable(changeStream.stream))
    val source = Source.fromPublisher(publisher).mapConcat[Change[T]] {
      case (docId, Some(doc)) =>
        Json.fromJson[T](Json.parse(compact(render(doc)))) match {
          case JsSuccess(obj, path) =>
            Update(docId, obj) :: Nil // Was of correct type
          case JsError(errors) =>
            Nil
        }
      case (docId, None) =>
        Deletion[T](docId) :: Nil
      case _ =>
        Nil
    }
    // Materialize with future marking the end of the change stream
    source.watchTermination() { (_, f) =>
      // Add hook to close the stream
      f.onComplete { _ => changeStream.close }
      f.map(_ => ())
    }
  }

}

object ChangeFeed {

  sealed trait Change[T]
  case class Update[T](id: String, obj: T) extends Change[T]
  case class Deletion[T](id: String) extends Change[T]

}