package soxx.scrappers

import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import akka.testkit.{ ImplicitSender, TestKit }
import mockws.{MockWS, MockWSHelpers}
import io.findify.s3mock.S3Mock

import scala.concurrent.duration._
import scala.concurrent.Await
import java.nio.file.{ Files, Paths }

import resource._
import akka.actor._
import io.minio.MinioClient
import play.api.Application
import play.api.mvc.Results._
import play.api.libs.ws.WSClient
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.inject.bind

import soxx.mongowrapper._

/** Scrapper integration tests. Ugly stuff here, beware!
  *
  * Basically, there are traits that help test image indexing and downloading
  * (both to the filesystem and to S3), here's a little scheme that explains what they do:
  *
  * BaseScrapperTest       The base test trait, others mix it in, it tests indexing
  * |                      (since it's same for every method anyway), starts S3,
  * |                      and does some basic clean-up when it's done (like stopping S3)
  * |
  * \___ FileScrapperTest  The test trait to test downloading to the filesystem.
  * |                      Cleans up the temporary directory and the file it creates when it's done
  * |
  * \___ S3ScrapperTest    Test test trait to test uploading indexed images to S3.
  *                        Doesn't require any special cleanup, it just calls the parent cleanup method
  */
class ScrappersSpec extends TestKit(ActorSystem("ScrappersSpec"))
    with ImplicitSender
    with FreeSpecLike
    with Matchers
    with BeforeAndAfterAll
    with MockWSHelpers
    with ScalaFutures {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  trait BaseScrapperTest {

    // S3 is always started, but isn't used if the app doesn't require it.
    // This has been done to work around the fact that the S3 messaging
    // actor is started with the application and therefore S3 needs to be
    // started before the application starts
    val s3 = S3Mock(port = 9999)
    s3.start

    // The WS mock addresses
    def ws: MockWS

    // The actor name used
    def actorName: String

    // The class to test
    def scrapperClass: Class[_ <: GenericScrapper]

    // The application
    def app: Application

    lazy val db = app.injector.instanceOf[Mongo].db

    val actor = system.actorOf(
      Props(
        scrapperClass,
        actorName,
        "http://example.com",
        "favicon.ico",
        app.injector
      ),
      actorName
    )

    // Indexing is same for everyone

    actor ! StartIndexing(1, Some(1))

    actor ! ScrapperStatusMsg
    expectMsg(ScrapperStatus(actorName, true, false)) // Scrapping process is started
    expectMsg(ScrapperStatus(actorName, false, false)) // The indexing has finished

    // Child tests should call to this parent implementation too
    def cleanup() {
      Await.result(db.drop().toFuture, 5.seconds)

      s3.stop
    }
  }

  trait FileScrapperTest extends BaseScrapperTest {
    override def app =
      new GuiceApplicationBuilder()
        .configure(
          "soxx.mongo.dbName" -> "soxx_test",

          "soxx.scrappers.downloadDirectory" -> tempDir.toString,
        )
        .overrides(bind[WSClient].to(ws))
        .build

    lazy val tempDir = Files.createTempDirectory(self.path.name)
    lazy val imagePath = Paths.get(tempDir.toString, "image_hash.jpg")

    try {
      actor ! StartDownloading

      actor ! ScrapperStatusMsg
      expectMsg(ScrapperStatus(actorName, false, true)) // Downloading process is started
      expectMsg(ScrapperStatus(actorName, false, false)) // Downloading process has finished

      Files.exists(imagePath) shouldBe true

      for (src <- managed(scala.io.Source.fromFile(imagePath.toString))) {
        src.mkString shouldBe "DOWNLOADED"
      }
    } finally {
      super.cleanup()

      Files.delete(imagePath)
      Files.delete(tempDir)
    }
  }

  trait S3ScrapperTest extends BaseScrapperTest {
    override def app =
      new GuiceApplicationBuilder()
        .configure(
          "soxx.mongo.dbName" -> "soxx_test",

          "soxx.s3.enabled" -> true,
          "soxx.s3.endpoint" -> "http://localhost:9999"
        )
        .overrides(bind[WSClient].to(ws))
        .build

    try {
      actor ! StartDownloading

      actor ! ScrapperStatusMsg
      expectMsg(ScrapperStatus(actorName, false, true)) // Downloading process is started
      expectMsg(ScrapperStatus(actorName, false, false)) // Downloading process has finished

      whenReady(db.getCollection[Image]("images").find().toFuture) { case Seq(img) =>
        img shouldBe 's3
        img.s3url shouldBe Some("http://localhost:9999/soxx-images/image_hash.jpg")
      }

      val minio = new MinioClient("http://localhost:9999")
      for (
        inpStream <- managed(minio.getObject("soxx-images", "image_hash.jpg"));
        src <- managed(scala.io.Source.fromInputStream(inpStream))
      ) {
        src.mkString shouldBe "DOWNLOADED"
      }
    } finally {
      super.cleanup()
    }
  }

  "OldDanbooru scrapper should" - {
    val _actorName = "test_old_danbooru"
    val _scrapperClass = classOf[OldDanbooruScrapper]
    val _ws = MockWS {
      case ("GET", "http://example.com/index.php?page=dapi&s=post&q=index") =>
        Action { Ok("""<posts count="1"></posts>""") }
      case ("GET", "http://example.com/index.php?page=dapi&s=post&q=index&pid=1&json=1") =>
        Action {
          Ok(
            (for (src <- managed(scala.io.Source.fromInputStream(getClass.getResourceAsStream("/scrappers/old_danbooru_image.json"))))
            yield src.mkString
            ).opt.get
          )
        }
      case ("GET", "http://example.com/images/2486/image_name.jpg") =>
        Action { Ok("DOWNLOADED")  }
    }

    "index and download images to" - {
      "the filesystem" in new FileScrapperTest {
        override def actorName = f"${_actorName}_fs"
        override def scrapperClass = _scrapperClass
        override def ws = _ws
      }

      "S3" in new S3ScrapperTest {
        override def actorName = f"${_actorName}_s3"
        override def scrapperClass = _scrapperClass
        override def ws = _ws
      }
    }
  }


  "NewDanbooru scrapper should" - {
    val _actorName = "test_new_danbooru"
    val _scrapperClass = classOf[NewDanbooruScrapper]
    val _ws = MockWS {
      case ("GET", "http://example.com/counts/posts.json") =>
        Action { Ok("""{"counts": {"posts": 2}}""") }
      case ("GET", "http://example.com/posts.json") =>
        Action {
          Ok(
            (for (src <- managed(scala.io.Source.fromInputStream(getClass.getResourceAsStream("/scrappers/new_danbooru_image.json"))))
              yield src.mkString
            ).opt.get
          )
        }
      case ("GET", "http://example.com/data/image_hash.jpg") =>
        Action { Ok("DOWNLOADED")  }
    }

    "index and download images to" - {
      "the filesystem" in new FileScrapperTest {
        override def actorName = f"${_actorName}_fs"
        override def scrapperClass = _scrapperClass
        override def ws = _ws
      }

      "S3" in new S3ScrapperTest {
        override def actorName = f"${_actorName}_s3"
        override def scrapperClass = _scrapperClass
        override def ws = _ws
      }
    }
  }


  "Moebooru scrapper should" - {
    val _actorName = "test_new_moebooru"
    val _scrapperClass = classOf[MoebooruScrapper]
    val _ws = MockWS {
      case ("GET", "http://example.com/post.xml") =>
        Action { Ok("""<posts count="1"></posts>""") }
      case ("GET", "http://example.com/post.json") =>
        Action {
          Ok(
            (for (src <- managed(scala.io.Source.fromInputStream(getClass.getResourceAsStream("/scrappers/moebooru_image.json"))))
              yield src.mkString
            ).opt.get
          )
        }
      case ("GET", "http://example.com/data/image_hash.jpg") =>
        Action { Ok("DOWNLOADED")  }
    }


    "index and download images to" - {
      "the filesystem" in new FileScrapperTest {
        override def actorName = f"${_actorName}_fs"
        override def scrapperClass = _scrapperClass
        override def ws = _ws
      }

      "S3" in new S3ScrapperTest {
        override def actorName = f"${_actorName}_s3"
        override def scrapperClass = _scrapperClass
        override def ws = _ws
      }
    }
  }
}
