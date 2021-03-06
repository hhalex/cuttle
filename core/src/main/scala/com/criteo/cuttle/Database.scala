package com.criteo.cuttle

import doobie.imports._
import doobie.hikari.imports._

import cats.data.{NonEmptyList}
import cats.implicits._

import io.circe._
import io.circe.parser._

import scala.util._

import java.time._

case class DatabaseConfig(host: String, port: Int, database: String, user: String, password: String)

object Database {

  implicit val DateTimeMeta: Meta[Instant] = Meta[java.sql.Timestamp].nxmap(
    x => Instant.ofEpochMilli(x.getTime),
    x => new java.sql.Timestamp(x.toEpochMilli)
  )

  implicit val ExecutionStatusMeta: Meta[ExecutionStatus] = Meta[Boolean].xmap(
    x => if (x != null && x) ExecutionSuccessful else ExecutionFailed,
    x =>
      x match {
        case ExecutionSuccessful => true
        case ExecutionFailed => false
        case x => sys.error(s"Unexpected ExecutionLog status to write in database: $x")
    }
  )

  implicit val JsonMeta: Meta[Json] = Meta[String].nxmap(
    x => parse(x).fold(e => throw e, identity),
    x => x.noSpaces
  )

  val schemaEvolutions = List(
    sql"""
      CREATE TABLE executions (
        id          CHAR(36) NOT NULL,
        job         VARCHAR(1000) NOT NULL,
        start_time  DATETIME NOT NULL,
        end_time    DATETIME NOT NULL,
        context_id  VARCHAR(1000) NOT NULL,
        success     BOOLEAN NOT NULL,
        PRIMARY KEY (id)
      ) ENGINE = INNODB;

      CREATE INDEX execution_by_context_id ON executions (context_id);
      CREATE INDEX execution_by_job ON executions (job);
      CREATE INDEX execution_by_start_time ON executions (start_time);

      CREATE TABLE paused_jobs (
        id          VARCHAR(1000) NOT NULL,
        PRIMARY KEY (id)
      ) ENGINE = INNODB;

      CREATE TABLE executions_streams (
        id          CHAR(36) NOT NULL,
        streams     TEXT
      ) ENGINE = INNODB;
    """
  )

  private val doSchemaUpdates =
    (for {
      _ <- sql"""
        CREATE TABLE IF NOT EXISTS schema_evolutions (
          schema_version  SMALLINT NOT NULL,
          schema_update   DATETIME NOT NULL,
          PRIMARY KEY     (schema_version)
        ) ENGINE = INNODB
      """.update.run

      currentSchemaVersion <- sql"""
        SELECT MAX(schema_version) FROM schema_evolutions
      """.query[Option[Int]].unique.map(_.getOrElse(0))

      _ <- schemaEvolutions.map(_.update).drop(currentSchemaVersion).zipWithIndex.foldLeft(NoUpdate) {
        case (evolutions, (evolution, i)) =>
          evolutions *> evolution.run *> sql"""
            INSERT INTO schema_evolutions (schema_version, schema_update)
            VALUES (${i + 1}, ${Instant.now()})
          """.update.run
      }
    } yield ())

  def connect(c: DatabaseConfig): XA = {
    val xa = (for {
      hikari <- HikariTransactor[IOLite](
        "com.mysql.cj.jdbc.Driver",
        s"jdbc:mysql://${c.host}:${c.port}/${c.database}?serverTimezone=UTC&useSSL=false&allowMultiQueries=true",
        c.user,
        c.password
      )
      _ <- hikari.configure { datasource =>
        IOLite.primitive( /* Configure datasource if needed */ ())
      }
    } yield hikari).unsafePerformIO
    doSchemaUpdates.transact(xa).unsafePerformIO
    xa
  }

  def configFromEnv: DatabaseConfig = {
    def env(variable: String, default: Option[String] = None) =
      Option(System.getenv(variable)).orElse(default).getOrElse(sys.error(s"Missing env ${'$' + variable}"))
    DatabaseConfig(
      env("MYSQL_HOST", Some("localhost")),
      Try(env("MYSQL_PORT", Some("3306")).toInt).getOrElse(3306),
      env("MYSQL_DATABASE"),
      env("MYSQL_USER"),
      env("MYSQL_PASSWORD")
    )
  }
}

trait Queries {
  import Database._

  def logExecution(e: ExecutionLog, logContext: ConnectionIO[String]): ConnectionIO[Int] =
    for {
      contextId <- logContext
      result <- sql"""
        INSERT INTO executions (id, job, start_time, end_time, success, context_id)
        VALUES (${e.id}, ${e.job}, ${e.startTime}, ${e.endTime}, ${e.status}, ${contextId})
        """.update.run
    } yield result

  def getExecutionLogSize(jobs: Set[String]): ConnectionIO[Int] =
    (sql"""
      SELECT COUNT(*) FROM executions WHERE """ ++ Fragments.in(fr"job", NonEmptyList.fromListUnsafe(jobs.toList)))
      .query[Int]
      .unique

  def getExecutionLog(contextQuery: Fragment,
                      jobs: Set[String],
                      sort: String,
                      asc: Boolean,
                      offset: Int,
                      limit: Int): ConnectionIO[List[ExecutionLog]] = {
    val orderBy = (sort, asc) match {
      case ("context", true) => sql"ORDER BY context_id ASC"
      case ("context", false) => sql"ORDER BY context_id DESC"
      case ("job", true) => sql"ORDER BY job ASC"
      case ("job", false) => sql"ORDER BY job DESC"
      case ("status", true) => sql"ORDER BY success ASC"
      case ("status", false) => sql"ORDER BY success DESC"
      case ("startTime", true) => sql"ORDER BY start_time ASC"
      case ("startTime", false) => sql"ORDER BY start_time DESC"
      case (_, true) => sql"ORDER BY end_time ASC"
      case _ => sql"ORDER BY end_time DESC"
    }
    (sql"""
      SELECT executions.id, job, start_time, end_time, contexts.json AS context, success
      FROM executions INNER JOIN (""" ++ contextQuery ++ sql""") contexts
      ON executions.context_id = contexts.id WHERE """ ++ Fragments.in(
      fr"job",
      NonEmptyList.fromListUnsafe(jobs.toList)) ++ orderBy ++ sql""" LIMIT $limit OFFSET $offset""")
      .query[(String, String, Instant, Instant, Json, ExecutionStatus)]
      .list
      .map(_.map {
        case (id, job, startTime, endTime, context, status) =>
          ExecutionLog(id, job, Some(startTime), Some(endTime), context, status)
      })
  }

  def getExecutionById(contextQuery: Fragment, id: String): ConnectionIO[Option[ExecutionLog]] =
    (sql"""
      SELECT executions.id, job, start_time, end_time, contexts.json AS context, success
      FROM executions INNER JOIN (""" ++ contextQuery ++ sql""") contexts
      ON executions.context_id = contexts.id WHERE executions.id = $id""")
      .query[(String, String, Instant, Instant, Json, ExecutionStatus)]
      .option
      .map(_.map {
        case (id, job, startTime, endTime, context, status) =>
          ExecutionLog(id, job, Some(startTime), Some(endTime), context, status)
      })

  def unpauseJob[S <: Scheduling](job: Job[S]): ConnectionIO[Int] =
    sql"""
      DELETE FROM paused_jobs WHERE id = ${job.id}
    """.update.run

  def pauseJob[S <: Scheduling](job: Job[S]): ConnectionIO[Int] =
    unpauseJob(job) *> sql"""
      INSERT INTO paused_jobs VALUES (${job.id});
    """.update.run

  def getPausedJobIds: ConnectionIO[Set[String]] =
    sql"SELECT id FROM paused_jobs"
      .query[String]
      .to[Set]

  def archiveStreams(id: String, streams: String): ConnectionIO[Int] =
    sql"""
      INSERT INTO executions_streams (id, streams) VALUES
      (${id}, ${streams})
    """.update.run

  def archivedStreams(id: String): ConnectionIO[Option[String]] =
    sql"SELECT streams FROM executions_streams WHERE id = ${id}"
      .query[String]
      .option
}
