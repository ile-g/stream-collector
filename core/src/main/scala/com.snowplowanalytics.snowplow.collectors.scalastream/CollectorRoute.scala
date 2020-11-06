/*
 * Copyright (c) 2013-2020 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, and
 * you may not use this file except in compliance with the Apache License
 * Version 2.0.  You may obtain a copy of the Apache License Version 2.0 at
 * http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Apache License Version 2.0 is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the Apache License Version 2.0 for the specific language
 * governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.collectors.scalastream

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpCookiePair
import akka.http.scaladsl.server.{Directive1, Route, RouteResult}
import akka.http.scaladsl.server.Directives._

import io.opentracing.{Span, Tracer}

import model.DntCookieMatcher
import monitoring.BeanRegistry
import scala.collection.JavaConverters._
import scala.util.{Success, Failure}

trait CollectorRoute {
  def collectorService: Service
  def tracer: Tracer

  private val headers = optionalHeaderValueByName("User-Agent") &
    optionalHeaderValueByName("Referer") &
    optionalHeaderValueByName("Raw-Request-URI")

  private val extractors = extractHost & extractClientIP & extractRequest

  def extractContentType: Directive1[ContentType] =
    extractRequestContext.map(_.request.entity.contentType)

  def collectorRoute =
    if (collectorService.enableDefaultRedirect) routes else rejectRedirect ~ routes

  def rejectRedirect: Route =
    path("r" / Segment) { _ =>
      complete(StatusCodes.NotFound -> "redirects disabled")
    }

  def traceRoute(inner: Span => Route): Route =
    requestContext => {
      val span = tracer.buildSpan("handle-request").start
      span.setTag("http.method", requestContext.request.method.name)
      span.setTag("http.url", requestContext.request.uri.toString)

      val fut = inner(span)(requestContext)
      fut.onComplete { result =>
        result match {
          case Success(RouteResult.Complete(response)) =>
            span.setTag("http.status", response.status.intValue)
          case Success(RouteResult.Rejected(rejections)) =>
            span.setTag("error", result.isFailure)
            span.log(Map("event" -> "error", "error.object" -> rejections).asJava)
          case Failure(e) =>
            span.setTag("error", result.isFailure)
            span.log(Map("event" -> "error", "error.object" -> e).asJava)
        }
        span.finish
      }(requestContext.executionContext)
      fut
    }

  // Activates the span only for the local thread
  def withActiveSpan[T](span: Span)(f: => T): T = {
    val scope = tracer.activateSpan(span)
    try {
      f
    } finally {
      scope.close
    }
  }

  def routes: Route =
    doNotTrack(collectorService.doNotTrackCookie) { dnt =>
      traceRoute { span =>
        cookieIfWanted(collectorService.cookieName) { reqCookie =>
          val cookie = reqCookie.map(_.toCookie)
          headers { (userAgent, refererURI, rawRequestURI) =>
            val qs = queryString(rawRequestURI)
            extractors { (host, ip, request) =>
              // get the adapter vendor and version from the path
              path(Segment / Segment) { (vendor, version) =>
                val path = collectorService.determinePath(vendor, version)
                post {
                  extractContentType { ct =>
                    entity(as[String]) { body =>
                      withActiveSpan(span) {
                        val (r, _) = collectorService.cookie(
                          qs,
                          Some(body),
                          path,
                          cookie,
                          userAgent,
                          refererURI,
                          host,
                          ip,
                          request,
                          pixelExpected = false,
                          doNotTrack = dnt,
                          Some(ct))
                        incrementRequests(r.status)
                        complete(r)
                      }
                    }
                  }
                } ~
                (get | head) {
                  withActiveSpan(span) {
                    val (r, _) = collectorService.cookie(
                      qs,
                      None,
                      path,
                      cookie,
                      userAgent,
                      refererURI,
                      host,
                      ip,
                      request,
                      pixelExpected = true,
                      doNotTrack = dnt)
                    incrementRequests(r.status)
                    complete(r)
                  }
                }
              } ~
              path("""ice\.png""".r | "i".r) { path =>
                (get | head) {
                  withActiveSpan(span) {
                    val (r, _) = collectorService.cookie(
                      qs,
                      None,
                      "/" + path,
                      cookie,
                      userAgent,
                      refererURI,
                      host,
                      ip,
                      request,
                      pixelExpected = true,
                      doNotTrack = dnt)
                    incrementRequests(r.status)
                    complete(r)
                  }
                }
              }
            }
          }
        }
      }
    } ~ corsRoute ~ healthRoute ~ crossDomainRoute ~ rootRoute ~ {
      BeanRegistry.collectorBean.incrementFailedRequests()
      complete(HttpResponse(404, entity = "404 not found"))
    }

  /**
   * Extract the query string from a request URI
   * @param rawRequestURI URI optionally extracted from the Raw-Request-URI header
   * @return the extracted query string or an empty string
   */
  def queryString(rawRequestURI: Option[String]): Option[String] = {
    val querystringExtractor = "^[^?]*\\?([^#]*)(?:#.*)?$".r
    rawRequestURI.flatMap {
      case querystringExtractor(qs) => Some(qs)
      case _ => None
    }
  }

  /**
    * Directive to extract a cookie if a cookie name is specified and if such a cookie exists
    * @param name Optionally configured cookie name
    */
  def cookieIfWanted(name: Option[String]): Directive1[Option[HttpCookiePair]] = name match {
    case Some(n) => optionalCookie(n)
    case None => optionalHeaderValue(_ => None)
  }

  /**
   * Directive to filter requests which contain a do not track cookie
   * @param cookieMatcher the configured do not track cookie to check against
   */
  def doNotTrack(cookieMatcher: Option[DntCookieMatcher]): Directive1[Boolean] =
    cookieIfWanted(cookieMatcher.map(_.name)).map { c =>
      (c, cookieMatcher) match {
        case (Some(actual), Some(config)) => config.matches(actual)
        case _ => false
      }
    }

  private def crossDomainRoute: Route = get {
    path("""crossdomain\.xml""".r) { _ =>
      complete(collectorService.flashCrossDomainPolicy)
    }
  }

  private def healthRoute: Route = get {
    path("health".r) { _ =>
      complete(HttpResponse(200, entity = "OK"))
    }
  }

  private def corsRoute: Route = options {
    extractRequest { request =>
      complete(collectorService.preflightResponse(request))
    }
  }

  private def rootRoute: Route = get {
    pathSingleSlash {
      complete(collectorService.rootResponse)
    }
  }

  private def incrementRequests(status: StatusCode): Unit = {
    if (List(StatusCodes.OK, StatusCodes.Found).contains(status)) {
      BeanRegistry.collectorBean.incrementSuccessfulRequests()
    } else {
      BeanRegistry.collectorBean.incrementFailedRequests()
    }
  }

}
