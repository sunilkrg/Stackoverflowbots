package in.internity.stackoverflow

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.coding.{Deflate, Gzip, NoCoding}
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.model.{Uri, _}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.Materializer
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import in.internity.TimeCache
import in.internity.models.Questions
import in.internity.twitter.TwitterCommunicator
import org.json4s.native.Serialization
import org.json4s.{DefaultFormats, native}

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/**
  * @author Shivansh <shiv4nsh@gmail.com>
  * @since 6/1/18
  */
class QuestionsActor(http: HttpExt, soUrl: String, key: String, twitterHandler: TwitterCommunicator)
                    (implicit as: ActorSystem, mat: Materializer, ec: ExecutionContext) extends Actor with ActorLogging {

  import Json4sSupport._

  val listOfQuestions = mutable.ListBuffer[Double]()
  implicit val formats: DefaultFormats.type = DefaultFormats
  implicit val serialization: Serialization.type = native.Serialization

  override def receive: Receive = {

    case Fetch(tag, fromDate) =>
      val fetch = fetchQuestions(tag, fromDate)
      fetch.map { a =>
        a.items.headOption.foreach { a =>
          if (TimeCache.getLatestTime(tag) < a.creation_date) {
            log.info(s"UpdatingTimeCache with:::${a.creation_date.toLong}")
            TimeCache.updateTimeAndTag(tag, a.creation_date)
          }
        }
        a.items.map { question =>
          println(question.owner)
          if (!listOfQuestions.contains(question.question_id)) {
            twitterHandler.formulateTweet(question).map{tweet=>
              log.info(s"Tweet: $tweet")
              twitterHandler.sendTweet(tweet)
            }


            listOfQuestions += question.question_id
          }
        }
      }

    case CallHeroku=>
      http.singleRequest(HttpRequest(uri = "https://internity-bots.herokuapp.com/")).onComplete({a=>
        println("Called heroku Responded with:",a.get.status)
      })
  }

  def fetchQuestions(tag: String, fromDate: Double): Future[Questions] = {
    val queryMap: Map[String, String] = Map(
      "key" -> key,
      "order" -> "desc",
      "sort" -> "creation",
      "tagged" -> tag,
      "site" -> "stackoverflow",
      "pagesize" -> "100",
      "fromdate" -> (fromDate.toLong + 1).toString
    )
    val url = Uri(soUrl).withQuery(Uri.Query(queryMap))
    log.info(s"Url:$url")
    http.singleRequest(HttpRequest(uri = url)).map(decodeResponse).flatMap(responseToQuestion)
  }

  private def responseToQuestion(response: HttpResponse): Future[Questions] = {
    response.status match {
      case StatusCodes.OK => Unmarshal(response.entity).to[Questions]
      case a =>
        log.error(s"Failed to get Actual response: Status Code :$a")
        Future.failed(new Exception(s"Failed to get Actual response: Status Code :$a"))
    }
  }

  def decodeResponse(response: HttpResponse): HttpResponse = {
    val decoder = response.encoding match {
      case HttpEncodings.gzip ⇒
        Gzip
      case HttpEncodings.deflate ⇒
        Deflate
      case HttpEncodings.identity ⇒
        NoCoding
      case _ => NoCoding
    }
    decoder.decodeMessage(response)
  }
}

object QuestionsActor {
  def props(soUrl: String, key: String, twitterCommunicator: TwitterCommunicator)(implicit as: ActorSystem, mat: Materializer, ec: ExecutionContext): Props = {
    Props(new QuestionsActor(Http(), soUrl, key, twitterCommunicator))
  }
}

case class Fetch(tag: String, fromDate: Double)
case object CallHeroku