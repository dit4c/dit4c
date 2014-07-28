package providers.hipache

import redis.RedisServer

object Hipache {

  case class ServerConfig(
      server: RedisServer,
      prefix: String)

  case class Frontend(
      name: String,
      domain: String)

  case class Backend(
      host: String,
      port: Int = 80,
      scheme: String = "http") {
    override def toString = s"$scheme://$host:$port"
  }

}