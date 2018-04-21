package soxx.scrappers

import javax.inject._

import akka.actor._
import scala.concurrent._
import scala.concurrent.duration._

import play.api.libs.ws._

import org.mongodb.scala._
import org.mongodb.scala.model._
import soxx.mongowrapper._

@Singleton
class ScrapperSupervisor @Inject()
  (
    implicit ec: ExecutionContext,
    ws: WSClient,
    mongo: Mongo
  ) extends Actor {
  override val supervisorStrategy = (new StoppingSupervisorStrategy).create()

  Await.result(
    mongo.DB
      .getCollection("imboard_info")
      .replaceOne(
        Document("_id" -> "safebooru"),
        Document(),
        UpdateOptions().upsert(true)
      )
      .toFuture(),
    Duration.Inf
  )

  context.actorOf(Props(new SafebooruScrapper), "safebooru-scrapper")

  override def receive = {
    case _ => ???
  }
}
