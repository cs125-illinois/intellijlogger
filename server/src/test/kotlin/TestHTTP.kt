package edu.illinois.cs.cs125.jeed.server

import edu.illinois.cs.cs125.intellijplugin.server.intellijlogger
import io.kotlintest.assertions.ktor.shouldHaveStatus
import io.kotlintest.specs.StringSpec
import io.ktor.application.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.withTestApplication
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody

class TestHTTP : StringSpec({
    "should provide info in response to GET" {
        withTestApplication(Application::intellijlogger) {
            handleRequest(HttpMethod.Get, "/") {
                addHeader("content-type", "application/json")
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)
                println(response.content.toString())
            }
        }
    }
})