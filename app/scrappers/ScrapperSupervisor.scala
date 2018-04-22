package soxx.scrappers

import javax.inject._

import akka.actor._
import scala.concurrent._
import scala.concurrent.duration._

import play.api.libs.ws._
import play.api.inject.ApplicationLifecycle

import org.mongodb.scala._
import org.mongodb.scala.model._
import soxx.mongowrapper._

@Singleton
class ScrapperSupervisor @Inject()
  (
    implicit ec: ExecutionContext,
    ws: WSClient,
    mongo: Mongo,
    lifecycle: ApplicationLifecycle
  ) extends Actor {
  override val supervisorStrategy = (new StoppingSupervisorStrategy).create()

  Await.result(
    mongo.db
      .getCollection("imboard_info")
      .replaceOne(
        Document("_id" -> "safebooru"),
        Document(),
        UpdateOptions().upsert(true)
      )
      .toFuture(),
    5 seconds
  )

  context.actorOf(Props(new SafebooruScrapper), "safebooru-scrapper")

  lifecycle.addStopHook { () =>
    context.stop(self)

    Future { }
  }

  override def receive = {
    case _ => ???
  }
}
