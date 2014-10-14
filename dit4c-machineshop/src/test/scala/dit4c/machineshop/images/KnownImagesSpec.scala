package dit4c.machineshop.images

import java.util.concurrent.TimeUnit

import org.specs2.mutable.Specification

import akka.util.Timeout
import scalax.file.FileSystem
import scalax.file.ramfs.RamFileSystem


class KnownImagesSpec extends Specification {
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = new Timeout(5, TimeUnit.SECONDS)

  "KnownImages" >> {

    "creates file is not present" >> {
      val fs: FileSystem = RamFileSystem()
      val file: scalax.file.Path = fs.fromString("/known_images.json")
      file.exists must beFalse
      val knownImages = new KnownImages(file)
      file.exists must beTrue
      file.inputStream().string() must_== "[]"
    }

    "can add/list/remove images" >> {
      val fs: FileSystem = RamFileSystem()
      val file: scalax.file.Path = fs.fromString("/known_images.json")
      val knownImages = new KnownImages(file)

      knownImages must beEmpty
      knownImages += KnownImage("test", "dit4c/test", "latest")
      knownImages must haveSize(1)

      knownImages.head.displayName must_== "test"
      knownImages.head.repository  must_== "dit4c/test"
      knownImages.head.tag         must_== "latest"

      {
        import spray.json._
        import DefaultJsonProtocol._
        val l = file.inputStream().string
          .parseJson.convertTo[List[Map[String,String]]]
        l.head("displayName") must_== "test"
        l.head("repository")  must_== "dit4c/test"
        l.head("tag")         must_== "latest"
      }

      knownImages -= knownImages.head
      knownImages must beEmpty
    }

    "images have IDs based on repository & tag" >> {
      val kiId = Function.untupled((KnownImage.apply _).tupled.andThen(_.id))
      
      kiId("Fedora", "fedora", "20") must beMatching("[a-z0-9]+")
      kiId("Foo", "foo/bar", "latest") must beMatching("[a-z0-9]+")
      
      kiId("Fedora 20", "fedora", "20") must_== kiId("Fedora", "fedora", "20")
      kiId("Fedora", "fedora", "20") must_!= kiId("Fedora", "fedora", "latest")
      
      kiId("Foo", "foo/bar", "latest") must_== kiId("Bar", "foo/bar", "latest")
      kiId("Foo", "foo/bar", "latest") must_!= kiId("Foo", "foo/baz", "latest")
    }

  }
}


