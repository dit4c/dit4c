package dit4c.scheduler.domain

import org.specs2.concurrent.ExecutionEnv
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck
import org.specs2.matcher.MatcherMacros
import akka.util.Timeout
import scala.concurrent.duration._
import akka.actor.ActorSystem
import org.scalacheck.Gen
import dit4c.scheduler.ScalaCheckHelpers
import akka.testkit.TestProbe
import org.specs2.matcher.Matcher
import scala.util.Random
import org.scalacheck.Arbitrary
import org.specs2.scalacheck.Parameters

class ClusterAggregateSpec(implicit ee: ExecutionEnv)
    extends Specification
    with ScalaCheck with MatcherMacros {

  import ScalaCheckHelpers._
  import ClusterAggregate._

  implicit val params = Parameters(minTestsOk = 20)
  implicit val arbSystem = Arbitrary(genSystem("ClusterAggregate"))

  "ClusterAggregate" >> {

    "GetState" >> {

      "initially returns Uninitialized" >> {
        // No state change between tests
        implicit val system =
          ActorSystem(s"ClusterAggregate-GetState-Uninitialized")

        prop({ aggregateId: String =>
          val probe = TestProbe()
          val clusterAggregate =
            system.actorOf(ClusterAggregate.props(aggregateId))
          probe.send(clusterAggregate, GetState)
          probe.expectMsgType[ClusterAggregate.State] must {
            be(ClusterAggregate.Uninitialized)
          }
        }).setGen(genAggregateId)
      }

      "returns state after being initialized" >> prop(
        (id: String, t: ClusterTypes.Value, system: ActorSystem) => {
          implicit val _ = system
          val aggregateId = s"somePrefix-$id"
          val probe = TestProbe()
          val clusterAggregate =
            system.actorOf(ClusterAggregate.props(aggregateId));
          {
            probe.send(clusterAggregate, Initialize(id, t))
            probe.receiveOne(1.second)
            probe.sender must be(clusterAggregate)
          } and
          {
            import scala.language.experimental.macros
            probe.send(clusterAggregate, GetState)
            probe.expectMsgType[ClusterAggregate.Cluster] must {
              matchA[ClusterAggregate.Cluster]
                .id(be_==(id))
                .`type`(t)
              }
            }
          }
      ).setGen1(genAggregateId)
    }

    "Initialize" >> {

      "becomes initialized" >> prop(
        (id: String, t: ClusterTypes.Value, system: ActorSystem) => {
          implicit val _ = system
          val aggregateId = s"somePrefix-$id"
          val probe = TestProbe()
          val clusterAggregate =
            system.actorOf(ClusterAggregate.props(aggregateId))
          val beExpectedInitializedState: Matcher[AnyRef] = beLike[AnyRef] {
            case state: ClusterAggregate.Cluster =>
              import scala.language.experimental.macros
              state must matchA[ClusterAggregate.Cluster]
                  .id(be_==(id))
                  .`type`(t)
          }
          // Get returned state after initialization and from GetState
          probe.send(clusterAggregate, Initialize(id, t))
          val initResponse = probe.expectMsgType[AnyRef]
          probe.send(clusterAggregate, GetState)
          val getStateResponse = probe.expectMsgType[AnyRef]
          // Test both
          (initResponse must beExpectedInitializedState) and
          (getStateResponse must beExpectedInitializedState)
        }
      ).setGen1(genAggregateId)
    }
  }

}