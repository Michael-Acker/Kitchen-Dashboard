package com.lifedashboard.tv.data

import android.content.Context
import android.util.Xml
import com.lifedashboard.tv.model.NewsHeadline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream

/**
 * BBC News RSS headlines. Parses the feed with XmlPullParser and returns the
 * first 5 items. Parse problems yield an empty list; network failures surface
 * as [IOException] for the UI to handle.
 */
class NewsRepository(private val context: Context) : NewsRepo {

    // Bounded waits: a hung socket must never stall a refresh pass forever.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun getHeadlines(): List<NewsHeadline> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(FEED_URL)
            .header("User-Agent", USER_AGENT)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("News feed request failed: HTTP ${response.code}")
                }
                val stream = response.body?.byteStream()
                    ?: throw IOException("Empty news feed response")
                parseFeed(stream)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseFeed(stream: InputStream): List<NewsHeadline> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, null)
        }
        val headlines = ArrayList<NewsHeadline>(MAX_HEADLINES)
        var eventType = parser.eventType
        var inItem = false
        var currentTag: String? = null
        var title = StringBuilder()
        var link = StringBuilder()

        while (eventType != XmlPullParser.END_DOCUMENT && headlines.size < MAX_HEADLINES) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (parser.name == "item") {
                        inItem = true
                        title = StringBuilder()
                        link = StringBuilder()
                    }
                }
                XmlPullParser.TEXT -> if (inItem) {
                    when (currentTag) {
                        "title" -> title.append(parser.text)
                        "link" -> link.append(parser.text)
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (inItem && parser.name == "item") {
                        inItem = false
                        val t = title.toString().trim()
                        val l = link.toString().trim()
                        if (t.isNotEmpty() && l.isNotEmpty()) {
                            headlines += NewsHeadline(
                                title = t,
                                source = SOURCE_NAME,
                                link = l
                            )
                        }
                    }
                    currentTag = null
                }
            }
            eventType = parser.next()
        }
        return headlines
    }

    companion object {
        private const val FEED_URL = "https://feeds.bbci.co.uk/news/rss.xml"
        private const val MAX_HEADLINES = 5
        private const val SOURCE_NAME = "BBC News"
        private const val USER_AGENT = "LifeDashboardTV/1.0 (Android TV)"
    }
}
