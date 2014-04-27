package fiddle
import acyclic.file
import spray.http._
import spray.http.HttpHeaders._
import spray.httpx.encoding.Gzip
import spray.routing.directives.CachingDirectives._
import scala.Some
import spray.routing.{RequestContext, SimpleRoutingApp}
import akka.actor.ActorSystem
import spray.routing.directives.CacheKeyer
import scala.collection.mutable
import java.security.{AccessControlException, Permission}
import java.io.FilePermission
import java.util.PropertyPermission
import java.lang.reflect.ReflectPermission
import java.net.SocketPermission

import spray.client.pipelining._

import play.api.libs.json._
import scala.concurrent.Future
import scala.tools.nsc.interpreter.Completion
import scala.reflect.internal.util.OffsetPosition
import spray.http.HttpHeaders.`Cache-Control`
import spray.http.CacheDirectives.{`max-age`, `public`}
import spray.http.HttpRequest
import spray.routing.RequestContext
import scala.Some
import spray.http.HttpResponse
import spray.http.CacheDirectives.`max-age`
import spray.routing._

object Server extends SimpleRoutingApp {
  implicit val system = ActorSystem()
  import system.dispatcher

  def main(args: Array[String]): Unit = {
    implicit val Default: CacheKeyer = CacheKeyer {
      case RequestContext(HttpRequest(_, uri, _, entity, _), _, _) => (uri, entity)
    }

    val clientFiles = Seq("/client-opt.js")
    val simpleCache = routeCache(maxCapacity = 1000)
    startServer("0.0.0.0", port = 8080) {
      cache(simpleCache) {
        encodeResponse(Gzip) {
          get {
            pathSingleSlash {
              complete{
                HttpEntity(
                  MediaTypes.`text/html`,
                  Static.page(
                    s"Client().gistMain([])",
                    clientFiles,
                    "Loading gist...")
                )
              }
            } ~
            path("gist" / Segments){ i =>
              complete{
                HttpEntity(
                  MediaTypes.`text/html`,
                  Static.page(
                    s"Client().gistMain(${JsArray(i.map(JsString)).toString()})",
                    clientFiles,
                    "Loading gist..."
                  )
                )
              }
            } ~
            getFromResourceDirectory("")
          } ~
          post {
            path("compile" ~ (Slash ~ Segment).?){ s =>
              compileStuff(_, Compiler.packageUserFiles(_, s.getOrElse("")))
            } ~
            path("optimize" ~ (Slash ~ Segment).?){ s =>
              compileStuff(_, Compiler.optimize(_, s.getOrElse("")))
            } ~
            path("preoptimize" ~ (Slash ~ Segment).?){ s =>
              compileStuff(_, Compiler.deadCodeElimination(_, s.getOrElse("")))
            } ~
            path("extdeps" ~ (Slash ~ Segment).?){ s =>
              complete{
                Compiler.packageJS(Compiler.scalaJSClassPath, s.getOrElse(""))
              }
            } ~
            path("export"){
              formFields("compiled", "source"){
                renderCode(_, Seq("/page-opt.js"), _, "Page().exportMain()", analytics = false)
              }
            } ~
            path("import"){
              formFields("compiled", "source"){
                renderCode(_, clientFiles, _, "Client().importMain()", analytics = true)
              }
            } ~
            path("complete" / Segment / IntNumber){
              completeStuff
            }
          }
        }
      }
    }
  }
  def renderCode(compiled: String, srcFiles: Seq[String], source: String, bootFunc: String, analytics: Boolean) = {

    complete{
      HttpEntity(
        MediaTypes.`text/html`,
        Static.page(bootFunc, srcFiles, source, compiled, analytics)
      )
    }
  }

  def completeStuff(flag: String, offset: Int)(ctx: RequestContext): Unit = {
//    setSecurityManager
    for(res <- Compiler.autocomplete(ctx.request.entity.asString, flag, offset)){
      val response = JsArray(
        res.map{case (label, name) => JsArray(Seq(label, name).map(JsString))}
      )
      println(s"got autocomplete: sending $response")
      ctx.responder ! HttpResponse(
        entity=response.toString(),
        headers=List(
          `Access-Control-Allow-Origin`(spray.http.AllOrigins)
        )
      )
    }
  }

  def compileStuff(ctx: RequestContext, processor: Seq[(String, String)] => String): Unit = {

    val output = mutable.Buffer.empty[String]

    val res = Compiler.compile(
      ctx.request.entity.data.toByteArray,
      output.append(_)
    )

    val returned = res match {
      case None =>
        Json.obj(
          "success" -> false,
          "logspam" -> output.mkString
        )

      case Some(files) =>
        val code = processor(
          files.map(f => f.name -> new String(f.toByteArray))
        )

        Json.obj(
          "success" -> true,
          "logspam" -> output.mkString,
          "code" -> code
        )
    }

    ctx.responder ! HttpResponse(
      entity=returned.toString,
      headers=List(
        `Access-Control-Allow-Origin`(spray.http.AllOrigins)
      )
    )
  }
}
