package controllers.apiv1

import org.scalatest._,  org.scalatest.concurrent._
import org.scalatestplus.play._, org.scalatestplus.play.guice._
import play.api.test._, play.api.test.Helpers._
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent._, scala.concurrent.duration._

import play.api.libs.json._
import akka.actor.ActorSystem
import akka.stream.Materializer

import
  soxx.scrappers._,
  soxx.mongowrapper.Mongo,
  soxx.helpers.Helpers.RequestHelpers

/**
 * Add your spec here.
 * You can mock out a whole application including requests, plugins etc.
 *
 * For more information, see https://www.playframework.com/documentation/latest/ScalaTestingWithScalaTest
 */
class APIv1ControllerSpec extends fixture.WordSpec
    with ScalaFutures
    with OptionValues
    with Matchers
    with LoneElement {

  case class FixtureParam(ctrl: APIv1Controller, mongo: Mongo)

  override def withFixture(test: OneArgTest) = {
    implicit val app = new GuiceApplicationBuilder()
      .configure("soxx.mongo.dbName" -> "soxx_test")
      .build
    val inj = app.injector

    implicit val (ec, mat, system, mongo) =
      (inj.instanceOf[ExecutionContext],
        inj.instanceOf[Materializer],
        inj.instanceOf[ActorSystem],
        inj.instanceOf[Mongo])
    val ctrl = new APIv1Controller(stubControllerComponents())

    val theFixture = FixtureParam(ctrl, mongo)

    try super.withFixture(test.toNoArgTest(theFixture))
    finally {
      Await.result(mongo.db.drop().toFuture, 5.seconds)
    }
  }

  "APIv1Controller" should {

    "return info on a single imageboard" in { f =>
      Await.result(
        f.mongo.db.getCollection[BoardInfo]("imboard_info")
          .insertOne(BoardInfo("test_board", "favicon", 100)).toFuture,
        5.seconds
      )

      val res = contentAsJson(f.ctrl.imboard_info(Some("test_board"))(FakeRequest()))
      val binfo = res.as[Seq[BoardInfo]]

      binfo.loneElement shouldBe BoardInfo("test_board", "favicon", 100)
    }

    "return info on all imageboards" in { f =>
      Await.result(
        f.mongo.db.getCollection[BoardInfo]("imboard_info")
          .insertMany(Seq(
            BoardInfo("test_board", "favicon.ico", 100),
            BoardInfo("test_board2", "favicon.ico", 200)
          )).toFuture,
        5.seconds
      )

      val res = contentAsJson(f.ctrl.imboard_info(None)(FakeRequest()))

      val binfo = res.as[Seq[BoardInfo]]

      binfo should contain allOf (
        BoardInfo("test_board", "favicon.ico", 100),
        BoardInfo("test_board2", "favicon.ico", 200)
      )
    }

    "return a single image by id" in { f =>
      import org.bson.types.ObjectId

      val oid = new ObjectId()
      val img = Image(
        height = 100, width = 100, tags = Seq("a", "tag"),
        md5 = "nope", extension = ".png", _id = oid,
        from = Seq(
          From(id = 1, name = "test_from", score = 0, post = "", image = "nope.png", thumbnail = "")
        )
      )

      Await.result(
        f.mongo.db.getCollection[Image]("images").insertOne(img).toFuture,
        5.seconds
      )

      // Semicolons are intentiaonal here
      val req = FakeRequest()
      val res = contentAsJson(f.ctrl.image(oid.toString)(req));
      (res \ "ok").get shouldBe JsBoolean(true);


      val fimg = (res \ "result").get.as[FrontendImage]

      fimg.height shouldBe img.height
      fimg.width shouldBe img.width
      fimg.tags shouldBe img.tags
      fimg._id shouldBe img._id
    }
  }
}
