package services

import akka.actor.ActorSystem
import akka.persistence.query.Offset
import akka.persistence.query.PersistenceQuery
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import domain.TaggingEventAdapter
import scala.concurrent.Future
import akka.stream.ActorMaterializer

class InstanceCreatorLookupService(implicit system: ActorSystem) {

  protected implicit val mat: ActorMaterializer = ActorMaterializer()

  val queries =
    PersistenceQuery(system)
      .readJournalFor[CassandraReadJournal](CassandraReadJournal.Identifier)

  def getUserIdOfInstanceCreator(instanceId: String): Future[Option[String]] =
      queries.currentEventsByTag(TaggingEventAdapter.tagForUserCreatedInstance(instanceId), Offset.noOffset)
        .filter { e =>
          e.persistenceId.startsWith("User-") &&
          e.event.isInstanceOf[domain.user.CreatedInstance]
        }
        .map(_.persistenceId.substring(5))
        .take(1)
        .runFold[Option[String]](None)((_, v) => Some(v))

}