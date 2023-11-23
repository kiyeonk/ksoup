package com.fleeksoft.ksoup.network

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.parser.Parser
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking

/**
 * Use to fetch and parse a HTML page.
 *
 * Use examples:
 *
 *  * `Document doc = Ksoup.parseGetRequest("http://example.com")`
 *
 * @param url URL to connect to. The protocol must be `http` or `https`.
 * @return sane HTML
 *
 */
public fun Ksoup.parseGetRequest(
    url: String,
    httpRequestBuilder: HttpRequestBuilder.() -> Unit = {},
    parser: Parser = Parser.htmlParser(),
): Document {
    var finalUrl: String = url
    val response: String = runBlocking {
        val httpResponse = NetworkHelper.instance.get(finalUrl, httpRequestBuilder = httpRequestBuilder)
//        url can be changed after redirection
        finalUrl = httpResponse.request.url.toString()
        httpResponse.bodyAsText()
    }
    return parse(html = response, parser = parser, baseUri = finalUrl)
}

/**
 * Use to fetch and parse a HTML page.
 *
 * Use examples:
 *
 *  * `Document doc = Ksoup.parseSubmitRequest("http://example.com", params = mapOf("param1Key" to "param1Value"))`
 *
 * @param url URL to connect to. The protocol must be `http` or `https`.
 * @return sane HTML
 *
 */
public fun Ksoup.parseSubmitRequest(
    url: String,
    params: Map<String, String> = emptyMap(),
    httpRequestBuilder: HttpRequestBuilder.() -> Unit = {},
    parser: Parser = Parser.htmlParser(),
): Document {
    var finalUrl: String = url
    val result: String =
        runBlocking {
            val httpResponse = NetworkHelper.instance.submitForm(
                url = url,
                params = params,
                httpRequestBuilder = httpRequestBuilder
            )
//            url can be changed after redirection
            finalUrl = httpResponse.request.url.toString()
            httpResponse.bodyAsText()
        }
    return parse(html = result, parser = parser, baseUri = finalUrl)
}


/**
 * Use to fetch and parse a HTML page.
 *
 * Use examples:
 *
 *  * `Document doc = Ksoup.parsePostRequest("http://example.com")`
 *
 * @param url URL to connect to. The protocol must be `http` or `https`.
 * @return sane HTML
 *
 */
public fun Ksoup.parsePostRequest(
    url: String,
    httpRequestBuilder: HttpRequestBuilder.() -> Unit = {},
    parser: Parser = Parser.htmlParser(),
): Document {
    var finalUrl: String = url
    val result: String =
        runBlocking {
            val httpResponse = NetworkHelper.instance.post(
                url = url,
                httpRequestBuilder = httpRequestBuilder
            )
//            url can be changed after redirection
            finalUrl = httpResponse.request.url.toString()
            httpResponse.bodyAsText()
        }
    return parse(html = result, parser = parser, baseUri = finalUrl)
}