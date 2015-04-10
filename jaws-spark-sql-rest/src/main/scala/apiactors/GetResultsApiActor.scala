package apiactors

import apiactors.ActorOperations._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.hive.HiveContext
import com.google.common.base.Preconditions
import com.xpatterns.jaws.data.DTO.Column
import com.xpatterns.jaws.data.DTO.Result
import com.xpatterns.jaws.data.utils.Utils
import server.Configuration
import akka.actor.Actor
import akka.actor.actorRef2Scala
import messages.GetResultsMessage
import net.liftweb.json._
import net.liftweb.json.DefaultFormats
import com.xpatterns.jaws.data.contracts.DAL
import org.apache.spark.scheduler.HiveUtils

import scala.util.Try

/**
 * Created by emaorhian
 */
class GetResultsApiActor(hdfsConf: org.apache.hadoop.conf.Configuration, hiveContext: HiveContext, dals: DAL) extends Actor {
  implicit val formats = DefaultFormats
  override def receive = {

    case message: GetResultsMessage =>
      {
        Configuration.log4j.info("[GetResultsMessage]: retrieving results for: " + message.queryID)

        var result: Result = null
        val tryGetResults = Try {

          val(offset, limit) = getOffsetAndLimit(message)
          val metaInfo = dals.loggingDal.getMetaInfo(message.queryID)

          metaInfo.resultsDestination match {
            // cassandra
            case 0 => {
              var result = dals.resultsDal.getResults(message.queryID)
              var endIndex = offset + limit
              if (endIndex > result.results.length) {
                endIndex = result.results.length
              }
              val res = result.results.slice(offset, endIndex)
              sender ! new Result(result.schema, res)

            }
            //hdfs
            case 1 => {
              val destinationPath = HiveUtils.getHdfsPath(Configuration.rddDestinationIp.get)
              result = getResults(offset, limit, destinationPath)

            }
            //tachyon
            case 2 => {
              val destinationPath = HiveUtils.getTachyonPath(Configuration.rddDestinationIp.get)
              result = getResults(offset, limit, destinationPath)

            }
            case _ => {
              Configuration.log4j.info("[GetResultsMessage]: Unidentified results path : " + metaInfo.resultsDestination)
              result = new Result
            }
          }
        }

        returnResult(tryGetResults, result, "GET results failed with the following message: ", sender)
      }

      def getResults(offset: Int, limit: Int, destinationPath: String): Result = {
        val schemaString = Utils.readFile(hdfsConf, Configuration.schemaFolder.getOrElse("jawsSchemaFolder") + "/" + message.queryID)
        val json = parse(schemaString)
        val schema = json.extract[Array[Column]]

        val resultsRDD: RDD[Tuple2[Object, Array[Object]]] = hiveContext.sparkContext.objectFile(HiveUtils.getRddDestinationPath(message.queryID, destinationPath))

        val filteredResults = resultsRDD.filter(tuple => tuple._1.asInstanceOf[Long] >= offset && tuple._1.asInstanceOf[Long] < offset + limit).collect()

        new Result(schema, filteredResults)

      }
  }

  def getOffsetAndLimit(message: GetResultsMessage): Tuple2[Int, Int] = {
    var offset = message.offset
    var limit = message.limit

    Option(offset) match {
      case None => {
        Configuration.log4j.info("[GetResultsMessage]: offset null... setting it on 0")
        offset = 0
      }
      case _ => {
        Configuration.log4j.info("[GetResultsMessage]: offset = " + offset)
      }
    }

    Option(limit) match {
      case None => {
        Configuration.log4j.info("[GetResultsMessage]: limit null... setting it on 100")
        limit = 100
      }
      case _ => {
        Configuration.log4j.info("[GetResultsMessage]: limit = " + limit)
      }
    }
    (offset, limit)
  }
}