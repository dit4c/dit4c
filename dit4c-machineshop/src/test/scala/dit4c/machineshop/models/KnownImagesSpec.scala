package dit4c.machineshop.models

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
      knownImages += KnownImage("test", "dit4c/test")
      knownImages must haveSize(1)
      knownImages.head.displayName must_== "test"
      knownImages.head.tagName must_== "dit4c/test"
      knownImages -= knownImages.head
      knownImages must beEmpty
    }


  }
}


