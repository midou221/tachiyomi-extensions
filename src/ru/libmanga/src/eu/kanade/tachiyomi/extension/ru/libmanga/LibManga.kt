package eu.kanade.tachiyomi.extension.ru.libmanga

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.ratelimit.RateLimitInterceptor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservable
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class LibManga : ConfigurableSource, HttpSource() {

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_2", 0x0000)
    }

    override val name: String = "Mangalib"

    override val lang = "ru"

    override val supportsLatest = true
    private fun imageContentTypeIntercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val response = chain.proceed(originalRequest)
        val urlRequest = originalRequest.url.toString()
        val possibleType = urlRequest.substringAfterLast("/").substringBefore("?").split(".")
        return if (urlRequest.contains("/chapters/") and (possibleType.size == 2)) {
            val realType = possibleType[1]
            val image = response.body?.byteString()?.toResponseBody("image/$realType".toMediaType())
            response.newBuilder().body(image).build()
        } else
            response
    }
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(RateLimitInterceptor(3))
        .addInterceptor { imageContentTypeIntercept(it) }
        .build()

    private val baseOrig: String = "https://mangalib.me"
    private val baseMirr: String = "https://mangalib.org"
    private var domain: String? = preferences.getString(DOMAIN_PREF, baseOrig)
    override val baseUrl: String = domain.toString()

    override fun headersBuilder() = Headers.Builder().apply {
        // User-Agent required for authorization through third-party accounts (mobile version for correct display in WebView)
        add("User-Agent", "Mozilla/5.0 (Linux; Android 10; SM-G980F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Mobile Safari/537.36")
        add("Accept", "image/webp,*/*;q=0.8")
        add("Referer", baseUrl)
    }

    private var csrfToken: String = ""

    private fun catalogHeaders() = Headers.Builder()
        .apply {
            add("Accept", "application/json, text/plain, */*")
            add("X-Requested-With", "XMLHttpRequest")
            add("x-csrf-token", csrfToken)
        }
        .build()

    // Latest
    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException("Not used") // popularMangaRequest()
    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        if (csrfToken.isEmpty()) {
            return client.newCall(popularMangaRequest(page))
                .asObservableSuccess()
                .flatMap { response ->
                    // Obtain token
                    val resBody = response.body!!.string()
                    csrfToken = "_token\" content=\"(.*)\"".toRegex().find(resBody)!!.groups[1]!!.value
                    return@flatMap fetchLatestMangaFromApi(page)
                }
        }
        return fetchLatestMangaFromApi(page)
    }

    private fun fetchLatestMangaFromApi(page: Int): Observable<MangasPage> {
        return client.newCall(POST("$baseUrl/latest-updates?page=$page", catalogHeaders()))
            .asObservable().doOnNext { response ->
                if (!response.isSuccessful) {
                    response.close()
                    if (response.code == 419) throw Exception("Для завершения авторизации необходимо перезапустить приложение с полной остановкой.") else throw Exception("HTTP error ${response.code}")
                }
            }
            .map { response ->
                latestUpdatesParse(response)
            }
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val resBody = response.body!!.string()
        val result = json.decodeFromString<JsonObject>(resBody)
        val itemsLatest = result["data"]?.jsonArray?.map { popularMangaFromElement(it) }
        if (itemsLatest != null) {
            val hasNextPage = result["next_page_url"]?.jsonPrimitive?.contentOrNull != null
            return MangasPage(itemsLatest, hasNextPage)
        }
        return MangasPage(emptyList(), false)
    }

    // Popular
    override fun popularMangaRequest(page: Int) = GET("$baseUrl/login", headers)
    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        if (csrfToken.isEmpty()) {
            return client.newCall(popularMangaRequest(page))
                .asObservableSuccess()
                .flatMap { response ->
                    // Obtain token
                    val resBody = response.body!!.string()
                    csrfToken = "_token\" content=\"(.*)\"".toRegex().find(resBody)!!.groups[1]!!.value
                    return@flatMap fetchPopularMangaFromApi(page)
                }
        }
        return fetchPopularMangaFromApi(page)
    }

    private fun fetchPopularMangaFromApi(page: Int): Observable<MangasPage> {
        return client.newCall(POST("$baseUrl/filterlist?dir=desc&sort=views&page=$page", catalogHeaders()))
            .asObservable().doOnNext { response ->
                if (!response.isSuccessful) {
                    response.close()
                    if (response.code == 419) throw Exception("Для завершения авторизации необходимо перезапустить приложение с полной остановкой.") else throw Exception("HTTP error ${response.code}")
                }
            }
            .map { response ->
                popularMangaParse(response)
            }
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val resBody = response.body!!.string()
        val result = json.decodeFromString<JsonObject>(resBody)
        val items = result["items"]!!.jsonObject
        val popularMangas = items["data"]?.jsonArray?.map { popularMangaFromElement(it) }
        if (popularMangas != null) {
            val hasNextPage = items["next_page_url"]?.jsonPrimitive?.contentOrNull != null
            return MangasPage(popularMangas, hasNextPage)
        }
        return MangasPage(emptyList(), false)
    }

    // Popular cross Latest
    private fun popularMangaFromElement(el: JsonElement) = SManga.create().apply {
        val slug = el.jsonObject["slug"]!!.jsonPrimitive.content
        title = when {
            isEng.equals("rus") && el.jsonObject["rus_name"]?.jsonPrimitive?.content.orEmpty().isNotEmpty() -> el.jsonObject["rus_name"]!!.jsonPrimitive.content
            isEng.equals("eng") && el.jsonObject["eng_name"]?.jsonPrimitive?.content.orEmpty().isNotEmpty() -> el.jsonObject["eng_name"]!!.jsonPrimitive.content
            else -> el.jsonObject["name"]!!.jsonPrimitive.content
        }
        thumbnail_url = if (el.jsonObject["covers"] != null) baseUrl + el.jsonObject["covers"]!!.jsonObject["default"]!!.jsonPrimitive.content
        else baseUrl + "/uploads/cover/" + slug + "/cover/" + el.jsonObject["cover"]!!.jsonPrimitive.content + "_250x350.jpg"
        url = "/$slug"
    }

    // Details
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        val dataStr = document
            .toString()
            .substringAfter("window.__DATA__ = ")
            .substringBefore("window._SITE_COLOR_")
            .substringBeforeLast(";")

        val dataManga = json.decodeFromString<JsonObject>(dataStr)["manga"]

        val manga = SManga.create()

        val body = document.select("div.media-info-list").first()
        val rawCategory = document.select(".media-short-info a.media-short-info__item").text()
        val category = when {
            rawCategory == "Комикс западный" -> "Комикс"
            rawCategory.isNotBlank() -> rawCategory
            else -> "Манга"
        }
        var rawAgeStop = document.select(".media-short-info .media-short-info__item[data-caution]").text()
        if (rawAgeStop.isEmpty()) {
            rawAgeStop = "0+"
        }

        val ratingValue = document.select(".media-rating__value").last().text().toFloat() * 2
        val ratingVotes = document.select(".media-rating__votes").last().text()
        val ratingStar = when {
            ratingValue > 9.5 -> "★★★★★"
            ratingValue > 8.5 -> "★★★★✬"
            ratingValue > 7.5 -> "★★★★☆"
            ratingValue > 6.5 -> "★★★✬☆"
            ratingValue > 5.5 -> "★★★☆☆"
            ratingValue > 4.5 -> "★★✬☆☆"
            ratingValue > 3.5 -> "★★☆☆☆"
            ratingValue > 2.5 -> "★✬☆☆☆"
            ratingValue > 1.5 -> "★☆☆☆☆"
            ratingValue > 0.5 -> "✬☆☆☆☆"
            else -> "☆☆☆☆☆"
        }
        val genres = document.select(".media-tags > a").map { it.text().capitalize() }
        manga.title = when {
            isEng.equals("rus") && dataManga!!.jsonObject["rusName"]?.jsonPrimitive?.content.orEmpty().isNotEmpty() -> dataManga.jsonObject["rusName"]!!.jsonPrimitive.content
            isEng.equals("eng") && dataManga!!.jsonObject["engName"]?.jsonPrimitive?.content.orEmpty().isNotEmpty() -> dataManga.jsonObject["engName"]!!.jsonPrimitive.content
            else -> dataManga!!.jsonObject["name"]!!.jsonPrimitive.content
        }
        manga.thumbnail_url = document.select(".media-header__cover").attr("src")
        manga.author = body.select("div.media-info-list__title:contains(Автор) + div a").joinToString { it.text() }
        manga.artist = body.select("div.media-info-list__title:contains(Художник) + div a").joinToString { it.text() }
        manga.status = if (document.html().contains("paper empty section")
        ) {
            SManga.LICENSED
        } else
            when (
                body.select("div.media-info-list__title:contains(Статус перевода) + div")
                    .text()
                    .toLowerCase(Locale.ROOT)
            ) {
                "продолжается" -> SManga.ONGOING
                "завершен" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
        manga.genre = category + ", " + rawAgeStop + ", " + genres.joinToString { it.trim() }

        val altName = if (dataManga!!.jsonObject["altNames"]?.jsonArray.orEmpty().isNotEmpty())
            "Альтернативные названия:\n" + dataManga.jsonObject["altNames"]!!.jsonArray.joinToString(" / ") { it.jsonPrimitive.content } + "\n\n"
        else ""

        val mediaNameLanguage = when {
            isEng.equals("eng") && dataManga!!.jsonObject["rusName"]?.jsonPrimitive?.content.orEmpty().isNotEmpty() -> dataManga.jsonObject["rusName"]!!.jsonPrimitive.content + "\n"
            isEng.equals("rus") && dataManga!!.jsonObject["engName"]?.jsonPrimitive?.content.orEmpty().isNotEmpty() -> dataManga.jsonObject["engName"]!!.jsonPrimitive.content + "\n"
            else -> ""
        }
        manga.description = mediaNameLanguage + ratingStar + " " + ratingValue + " (голосов: " + ratingVotes + ")\n" + altName + document.select(".media-description__text").text()
        return manga
    }

    // Chapters
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val redirect = document.html()
        if (redirect.contains("paper empty section")) {
            return emptyList()
        }
        val dataStr = document
            .toString()
            .substringAfter("window.__DATA__ = ")
            .substringBefore("window._SITE_COLOR_")
            .substringBeforeLast(";")

        val data = json.decodeFromString<JsonObject>(dataStr)
        val chaptersList = data["chapters"]!!.jsonObject["list"]?.jsonArray
        val slug = data["manga"]!!.jsonObject["slug"]!!.jsonPrimitive.content
        val branches = data["chapters"]!!.jsonObject["branches"]!!.jsonArray.reversed()
        val teams = data["chapters"]!!.jsonObject["teams"]!!.jsonArray
        val sortingList = preferences.getString(SORTING_PREF, "ms_mixing")

        val chapters: List<SChapter>? = if (branches.isNotEmpty()) {
            sortChaptersByTranslator(sortingList, chaptersList, slug, branches)
        } else {
            chaptersList
                ?.filter { it.jsonObject["status"]?.jsonPrimitive?.intOrNull != 2 }
                ?.map { chapterFromElement(it, sortingList, slug, null, null, teams, chaptersList) }
        }

        return chapters ?: emptyList()
    }

    private fun sortChaptersByTranslator
    (sortingList: String?, chaptersList: JsonArray?, slug: String, branches: List<JsonElement>): List<SChapter>? {
        var chapters: List<SChapter>? = null
        val volume = "(?<=/v)[0-9]+(?=/c[0-9]+)".toRegex()
        when (sortingList) {
            "ms_mixing" -> {
                val tempChaptersList = mutableListOf<SChapter>()
                for (currentBranch in branches.withIndex()) {
                    val branch = branches[currentBranch.index]
                    val teamId = branch.jsonObject["id"]!!.jsonPrimitive.int
                    val teams = branch.jsonObject["teams"]!!.jsonArray.filter { it.jsonObject["is_active"]?.jsonPrimitive?.intOrNull == 1 }
                    val teamsBranch = if (teams.size == 1)
                        teams[0].jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    else if (teams.isEmpty())
                        branch.jsonObject["teams"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content
                    else null
                    chapters = chaptersList
                        ?.filter { it.jsonObject["branch_id"]?.jsonPrimitive?.intOrNull == teamId && it.jsonObject["status"]?.jsonPrimitive?.intOrNull != 2 }
                        ?.map { chapterFromElement(it, sortingList, slug, teamId, branches) }
                    chapters?.let {
                        if ((tempChaptersList.size < it.size) && !groupTranslates.contains(teamsBranch.toString()))
                            tempChaptersList.addAll(0, it)
                        else
                            tempChaptersList.addAll(it)
                    }
                }
                chapters = tempChaptersList.distinctBy { volume.find(it.url)?.value + "--" + it.chapter_number }.sortedWith(compareBy({ -it.chapter_number }, { volume.find(it.url)?.value }))
            }
            "ms_combining" -> {
                val tempChaptersList = mutableListOf<SChapter>()
                for (currentBranch in branches.withIndex()) {
                    val branch = branches[currentBranch.index]
                    val teamId = branch.jsonObject["id"]!!.jsonPrimitive.int
                    val teams = branch.jsonObject["teams"]!!.jsonArray.filter { it.jsonObject["is_active"]?.jsonPrimitive?.intOrNull == 1 }
                    val teamsBranch = if (teams.size == 1)
                        teams[0].jsonObject["name"]?.jsonPrimitive?.contentOrNull
                    else if (teams.isEmpty())
                        branch.jsonObject["teams"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content
                    else null
                    chapters = chaptersList
                        ?.filter { it.jsonObject["branch_id"]?.jsonPrimitive?.intOrNull == teamId && it.jsonObject["status"]?.jsonPrimitive?.intOrNull != 2 }
                        ?.map { chapterFromElement(it, sortingList, slug, teamId, branches) }
                    if (!groupTranslates.contains(teamsBranch.toString()))
                        chapters?.let { tempChaptersList.addAll(it) }
                }
                chapters = tempChaptersList
            }
        }

        return chapters
    }

    private fun chapterFromElement
    (chapterItem: JsonElement, sortingList: String?, slug: String, teamIdParam: Int? = null, branches: List<JsonElement>? = null, teams: List<JsonElement>? = null, chaptersList: JsonArray? = null): SChapter {
        val chapter = SChapter.create()

        val volume = chapterItem.jsonObject["chapter_volume"]!!.jsonPrimitive.int
        val number = chapterItem.jsonObject["chapter_number"]!!.jsonPrimitive.content
        val chapterScanlatorId = chapterItem.jsonObject["chapter_scanlator_id"]!!.jsonPrimitive.int
        val isScanlatorId = teams?.filter { it.jsonObject["id"]?.jsonPrimitive?.intOrNull == chapterScanlatorId }
        val teamId = if (teamIdParam != null) "?bid=$teamIdParam" else ""

        val url = "$baseUrl/$slug/v$volume/c$number$teamId"

        chapter.setUrlWithoutDomain(url)

        val nameChapter = chapterItem.jsonObject["chapter_name"]?.jsonPrimitive?.contentOrNull
        val fullNameChapter = "Том $volume. Глава $number"
        chapter.scanlator = if (teams?.size == 1) teams[0].jsonObject["name"]?.jsonPrimitive?.content else if (isScanlatorId.orEmpty().isNotEmpty()) isScanlatorId!![0].jsonObject["name"]?.jsonPrimitive?.content else branches?.let { getScanlatorTeamName(it, chapterItem) } ?: if ((preferences.getBoolean(isScan_USER, false)) || (chaptersList?.distinctBy { it.jsonObject["username"]!!.jsonPrimitive.content }?.size == 1)) chapterItem.jsonObject["username"]!!.jsonPrimitive.content else null
        chapter.name = if (nameChapter.isNullOrBlank()) fullNameChapter else "$fullNameChapter - $nameChapter"
        chapter.date_upload = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .parse(chapterItem.jsonObject["chapter_created_at"]!!.jsonPrimitive.content.substringBefore(" "))?.time ?: 0L
        chapter.chapter_number = number.toFloat()

        return chapter
    }

    private fun getScanlatorTeamName(branches: List<JsonElement>, chapterItem: JsonElement): String? {
        var scanlatorData: String? = null
        for (currentBranch in branches.withIndex()) {
            val branch = branches[currentBranch.index].jsonObject
            val teams = branch["teams"]!!.jsonArray
            if (chapterItem.jsonObject["branch_id"]!!.jsonPrimitive.int == branch["id"]!!.jsonPrimitive.int) {
                for (currentTeam in teams.withIndex()) {
                    val team = teams[currentTeam.index].jsonObject
                    val scanlatorId = chapterItem.jsonObject["chapter_scanlator_id"]!!.jsonPrimitive.int
                    if ((scanlatorId == team.jsonObject["id"]!!.jsonPrimitive.int) ||
                        (scanlatorId == 0 && team["is_active"]!!.jsonPrimitive.int == 1)
                    ) return team["name"]!!.jsonPrimitive.content else scanlatorData = branch["teams"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content
                }
            }
        }
        return scanlatorData
    }

    // Pages
    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val redirect = document.html()
        if (!redirect.contains("window.__info")) {
            if (redirect.contains("hold-transition login-page")) {
                throw Exception("Для просмотра 18+ контента необходима авторизация через WebView")
            }
        }

        val chapInfo = document
            .select("script:containsData(window.__info)")
            .first()
            .html()
            .split("window.__info = ")
            .last()
            .trim()
            .split(";")
            .first()

        val chapInfoJson = json.decodeFromString<JsonObject>(chapInfo)
        val servers = chapInfoJson["servers"]!!.jsonObject.toMap()
        val defaultServer: String = chapInfoJson["img"]!!.jsonObject["server"]!!.jsonPrimitive.content
        val autoServer = setOf("secondary", "fourth", defaultServer, "compress")
        val imgUrl: String = chapInfoJson["img"]!!.jsonObject["url"]!!.jsonPrimitive.content

        val serverToUse = when (this.server) {
            null -> autoServer
            "auto" -> autoServer
            else -> listOf(this.server)
        }

        // Get pages
        val pagesArr = document
            .select("script:containsData(window.__pg)")
            .first()
            .html()
            .trim()
            .removePrefix("window.__pg = ")
            .removeSuffix(";")

        val pagesJson = json.decodeFromString<JsonArray>(pagesArr)
        val pages = mutableListOf<Page>()

        pagesJson.forEach { page ->
            val keys = servers.keys.filter { serverToUse.indexOf(it) >= 0 }.sortedBy { serverToUse.indexOf(it) }
            val serversUrls = keys.map {
                servers[it]?.jsonPrimitive?.contentOrNull + imgUrl + page.jsonObject["u"]!!.jsonPrimitive.content
            }.joinToString(separator = ",,") { it }
            pages.add(Page(page.jsonObject["p"]!!.jsonPrimitive.int, serversUrls))
        }

        return pages
    }

    private fun checkImage(url: String): Boolean {
        val response = client.newCall(Request.Builder().url(url).head().headers(headers).build()).execute()
        return response.isSuccessful && (response.header("content-length", "0")?.toInt()!! > 320)
    }

    override fun fetchImageUrl(page: Page): Observable<String> {
        if (page.imageUrl != null) {
            return Observable.just(page.imageUrl)
        }

        val urls = page.url.split(",,")
        if (urls.size == 1) {
            return Observable.just(urls[0])
        }

        return Observable.from(urls).filter { checkImage(it) }.first()
    }

    override fun imageUrlParse(response: Response): String = ""

    // Workaround to allow "Open in browser" use the
    private fun searchMangaByIdRequest(id: String): Request {
        return GET("$baseUrl/$id", headers)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_SLUG_SEARCH)) {
            val realQuery = query.removePrefix(PREFIX_SLUG_SEARCH)
            client.newCall(searchMangaByIdRequest(realQuery))
                .asObservableSuccess()
                .map { response ->
                    val details = mangaDetailsParse(response)
                    details.url = "/$realQuery"
                    MangasPage(listOf(details), false)
                }
        } else {
            client.newCall(searchMangaRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchMangaParse(response)
                }
        }
    }

    // Search
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (csrfToken.isEmpty()) {
            val tokenResponse = client.newCall(popularMangaRequest(page)).execute()
            val resBody = tokenResponse.body!!.string()
            csrfToken = "_token\" content=\"(.*)\"".toRegex().find(resBody)!!.groups[1]!!.value
        }
        val url = "$baseUrl/filterlist?page=$page".toHttpUrlOrNull()!!.newBuilder()
        if (query.isNotEmpty()) {
            url.addQueryParameter("name", query)
        }
        (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
            when (filter) {
                is CategoryList -> filter.state.forEach { category ->
                    if (category.state) {
                        url.addQueryParameter("types[]", category.id)
                    }
                }
                is FormatList -> filter.state.forEach { forma ->
                    if (forma.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (forma.isIncluded()) "format[include][]" else "format[exclude][]", forma.id)
                    }
                }
                is StatusList -> filter.state.forEach { status ->
                    if (status.state) {
                        url.addQueryParameter("status[]", status.id)
                    }
                }
                is StatusTitleList -> filter.state.forEach { title ->
                    if (title.state) {
                        url.addQueryParameter("manga_status[]", title.id)
                    }
                }
                is GenreList -> filter.state.forEach { genre ->
                    if (genre.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (genre.isIncluded()) "genres[include][]" else "genres[exclude][]", genre.id)
                    }
                }
                is OrderBy -> {
                    url.addQueryParameter("dir", if (filter.state!!.ascending) "asc" else "desc")
                    url.addQueryParameter("sort", arrayOf("rate", "name", "views", "created_at", "last_chapter_at", "chap_count")[filter.state!!.index])
                }
                is AgeList -> filter.state.forEach { age ->
                    if (age.state) {
                        url.addQueryParameter("caution[]", age.id)
                    }
                }
                is TagList -> filter.state.forEach { tag ->
                    if (tag.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (tag.isIncluded()) "tags[include][]" else "tags[exclude][]", tag.id)
                    }
                }
                is MyList -> filter.state.forEach { favorite ->
                    if (favorite.state != Filter.TriState.STATE_IGNORE) {
                        url.addQueryParameter(if (favorite.isIncluded()) "bookmarks[include][]" else "bookmarks[exclude][]", favorite.id)
                    }
                }
            }
        }
        return POST(url.toString(), catalogHeaders())
    }

    // Hack search method to add some results from search popup
    override fun searchMangaParse(response: Response): MangasPage {
        val searchRequest = response.request.url.queryParameter("name")
        val mangas = mutableListOf<SManga>()

        if (!searchRequest.isNullOrEmpty()) {
            val popupSearchHeaders = headers
                .newBuilder()
                .add("Accept", "application/json, text/plain, */*")
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            // +200ms
            val popup = client.newCall(
                GET("$baseUrl/search?query=$searchRequest", popupSearchHeaders)
            )
                .execute().body!!.string()

            val jsonList = json.decodeFromString<JsonArray>(popup)
            jsonList.forEach {
                mangas.add(popularMangaFromElement(it))
            }
        }
        val searchedMangas = popularMangaParse(response)

        // Filtered out what find in popup search
        mangas.addAll(
            searchedMangas.mangas.filter { search ->
                mangas.find { search.title == it.title } == null
            }
        )

        return MangasPage(mangas, searchedMangas.hasNextPage)
    }

    // Filters
    private class SearchFilter(name: String, val id: String) : Filter.TriState(name)
    private class CheckFilter(name: String, val id: String) : Filter.CheckBox(name)

    private class CategoryList(categories: List<CheckFilter>) : Filter.Group<CheckFilter>("Тип", categories)
    private class FormatList(formas: List<SearchFilter>) : Filter.Group<SearchFilter>("Формат выпуска", formas)
    private class StatusList(statuses: List<CheckFilter>) : Filter.Group<CheckFilter>("Статус перевода", statuses)
    private class StatusTitleList(titles: List<CheckFilter>) : Filter.Group<CheckFilter>("Статус тайтла", titles)
    private class GenreList(genres: List<SearchFilter>) : Filter.Group<SearchFilter>("Жанры", genres)
    private class TagList(tags: List<SearchFilter>) : Filter.Group<SearchFilter>("Теги", tags)
    private class AgeList(ages: List<CheckFilter>) : Filter.Group<CheckFilter>("Возрастное ограничение", ages)
    private class MyList(favorites: List<SearchFilter>) : Filter.Group<SearchFilter>("Мои списки", favorites)

    override fun getFilterList() = FilterList(
        OrderBy(),
        CategoryList(getCategoryList()),
        FormatList(getFormatList()),
        GenreList(getGenreList()),
        TagList(getTagList()),
        StatusList(getStatusList()),
        StatusTitleList(getStatusTitleList()),
        AgeList(getAgeList()),
        MyList(getMyList())
    )

    private class OrderBy : Filter.Sort(
        "Сортировка",
        arrayOf("Рейтинг", "Имя", "Просмотры", "Дате добавления", "Дате обновления", "Кол-во глав"),
        Selection(2, false)
    )

    /*
    * Use console
    * Object.entries(__FILTER_ITEMS__.types).map(([k, v]) => `SearchFilter("${v.label}", "${v.id}")`).join(',\n')
    * on /manga-list
    */
    private fun getCategoryList() = listOf(
        CheckFilter("Манга", "1"),
        CheckFilter("OEL-манга", "4"),
        CheckFilter("Манхва", "5"),
        CheckFilter("Маньхуа", "6"),
        CheckFilter("Руманга", "8"),
        CheckFilter("Комикс западный", "9")
    )

    private fun getFormatList() = listOf(
        SearchFilter("4-кома (Ёнкома)", "1"),
        SearchFilter("Сборник", "2"),
        SearchFilter("Додзинси", "3"),
        SearchFilter("Сингл", "4"),
        SearchFilter("В цвете", "5"),
        SearchFilter("Веб", "6")
    )

    /*
    * Use console
    * Object.entries(__FILTER_ITEMS__.status).map(([k, v]) => `SearchFilter("${v.label}", "${v.id}")`).join(',\n')
    * on /manga-list
    */
    private fun getStatusList() = listOf(
        CheckFilter("Продолжается", "1"),
        CheckFilter("Завершен", "2"),
        CheckFilter("Заморожен", "3"),
        CheckFilter("Заброшен", "4")
    )

    private fun getStatusTitleList() = listOf(
        CheckFilter("Онгоинг", "1"),
        CheckFilter("Завершён", "2"),
        CheckFilter("Анонс", "3"),
        CheckFilter("Приостановлен", "4"),
        CheckFilter("Выпуск прекращён", "5"),
    )

    /*
    * Use console
    * __FILTER_ITEMS__.genres.map(it => `SearchFilter("${it.name}", "${it.id}")`).join(',\n')
    * on /manga-list
    */
    private fun getGenreList() = listOf(
        SearchFilter("арт", "32"),
        SearchFilter("боевик", "34"),
        SearchFilter("боевые искусства", "35"),
        SearchFilter("вампиры", "36"),
        SearchFilter("гарем", "37"),
        SearchFilter("гендерная интрига", "38"),
        SearchFilter("героическое фэнтези", "39"),
        SearchFilter("детектив", "40"),
        SearchFilter("дзёсэй", "41"),
        SearchFilter("драма", "43"),
        SearchFilter("игра", "44"),
        SearchFilter("исекай", "79"),
        SearchFilter("история", "45"),
        SearchFilter("киберпанк", "46"),
        SearchFilter("кодомо", "76"),
        SearchFilter("комедия", "47"),
        SearchFilter("махо-сёдзё", "48"),
        SearchFilter("меха", "49"),
        SearchFilter("мистика", "50"),
        SearchFilter("научная фантастика", "51"),
        SearchFilter("омегаверс", "77"),
        SearchFilter("повседневность", "52"),
        SearchFilter("постапокалиптика", "53"),
        SearchFilter("приключения", "54"),
        SearchFilter("психология", "55"),
        SearchFilter("романтика", "56"),
        SearchFilter("самурайский боевик", "57"),
        SearchFilter("сверхъестественное", "58"),
        SearchFilter("сёдзё", "59"),
        SearchFilter("сёдзё-ай", "60"),
        SearchFilter("сёнэн", "61"),
        SearchFilter("сёнэн-ай", "62"),
        SearchFilter("спорт", "63"),
        SearchFilter("сэйнэн", "64"),
        SearchFilter("трагедия", "65"),
        SearchFilter("триллер", "66"),
        SearchFilter("ужасы", "67"),
        SearchFilter("фантастика", "68"),
        SearchFilter("фэнтези", "69"),
        SearchFilter("школа", "70"),
        SearchFilter("эротика", "71"),
        SearchFilter("этти", "72"),
        SearchFilter("юри", "73"),
        SearchFilter("яой", "74")
    )

    private fun getTagList() = listOf(
        SearchFilter("Азартные игры", "304"),
        SearchFilter("Алхимия", "225"),
        SearchFilter("Ангелы", "226"),
        SearchFilter("Антигерой", "175"),
        SearchFilter("Антиутопия", "227"),
        SearchFilter("Апокалипсис", "228"),
        SearchFilter("Армия", "229"),
        SearchFilter("Артефакты", "230"),
        SearchFilter("Боги", "215"),
        SearchFilter("Бои на мечах", "231"),
        SearchFilter("Борьба за власть", "231"),
        SearchFilter("Брат и сестра", "233"),
        SearchFilter("Будущее", "234"),
        SearchFilter("Ведьма", "338"),
        SearchFilter("Вестерн", "235"),
        SearchFilter("Видеоигры", "185"),
        SearchFilter("Виртуальная реальность", "195"),
        SearchFilter("Владыка демонов", "236"),
        SearchFilter("Военные", "179"),
        SearchFilter("Война", "237"),
        SearchFilter("Волшебники / маги", "281"),
        SearchFilter("Волшебные существа", "239"),
        SearchFilter("Воспоминания из другого мира", "240"),
        SearchFilter("Выживание", "193"),
        SearchFilter("ГГ женщина", "243"),
        SearchFilter("ГГ имба", "291"),
        SearchFilter("ГГ мужчина", "244"),
        SearchFilter("Геймеры", "241"),
        SearchFilter("Гильдии", "242"),
        SearchFilter("Глупый ГГ", "297"),
        SearchFilter("Гоблины", "245"),
        SearchFilter("Горничные", "169"),
        SearchFilter("Гяру", "178"),
        SearchFilter("Демоны", "151"),
        SearchFilter("Драконы", "246"),
        SearchFilter("Дружба", "247"),
        SearchFilter("Жестокий мир", "249"),
        SearchFilter("Животные компаньоны", "250"),
        SearchFilter("Завоевание мира", "251"),
        SearchFilter("Зверолюди", "162"),
        SearchFilter("Злые духи", "252"),
        SearchFilter("Зомби", "149"),
        SearchFilter("Игровые элементы", "253"),
        SearchFilter("Империи", "254"),
        SearchFilter("Квесты", "255"),
        SearchFilter("Космос", "256"),
        SearchFilter("Кулинария", "152"),
        SearchFilter("Культивация", "160"),
        SearchFilter("Легендарное оружие", "257"),
        SearchFilter("Лоли", "187"),
        SearchFilter("Магическая академия", "258"),
        SearchFilter("Магия", "168"),
        SearchFilter("Мафия", "172"),
        SearchFilter("Медицина", "153"),
        SearchFilter("Месть", "259"),
        SearchFilter("Монстр Девушки", "188"),
        SearchFilter("Монстры", "189"),
        SearchFilter("Музыка", "190"),
        SearchFilter("Навыки / способности", "260"),
        SearchFilter("Насилие / жестокость", "262"),
        SearchFilter("Наёмники", "261"),
        SearchFilter("Нежить", "263"),
        SearchFilter("Ниндая", "180"),
        SearchFilter("Обратный Гарем", "191"),
        SearchFilter("Огнестрельное оружие", "264"),
        SearchFilter("Офисные Работники", "181"),
        SearchFilter("Пародия", "265"),
        SearchFilter("Пираты", "340"),
        SearchFilter("Подземелья", "266"),
        SearchFilter("Политика", "267"),
        SearchFilter("Полиция", "182"),
        SearchFilter("Преступники / Криминал", "186"),
        SearchFilter("Призраки / Духи", "177"),
        SearchFilter("Путешествие во времени", "194"),
        SearchFilter("Разумные расы", "268"),
        SearchFilter("Ранги силы", "248"),
        SearchFilter("Реинкарнация", "148"),
        SearchFilter("Роботы", "269"),
        SearchFilter("Рыцари", "270"),
        SearchFilter("Самураи", "183"),
        SearchFilter("Система", "271"),
        SearchFilter("Скрытие личности", "273"),
        SearchFilter("Спасение мира", "274"),
        SearchFilter("Спортивное тело", "334"),
        SearchFilter("Средневековье", "173"),
        SearchFilter("Стимпанк", "272"),
        SearchFilter("Супергерои", "275"),
        SearchFilter("Традиционные игры", "184"),
        SearchFilter("Умный ГГ", "302"),
        SearchFilter("Учитель / ученик", "276"),
        SearchFilter("Философия", "277"),
        SearchFilter("Хикикомори", "166"),
        SearchFilter("Холодное оружие", "278"),
        SearchFilter("Шантаж", "279"),
        SearchFilter("Эльфы", "216"),
        SearchFilter("Якудза", "164"),
        SearchFilter("Япония", "280")

    )

    private fun getAgeList() = listOf(
        CheckFilter("Отсутствует", "0"),
        CheckFilter("16+", "1"),
        CheckFilter("18+", "2")
    )

    private fun getMyList() = listOf(
        SearchFilter("Читаю", "1"),
        SearchFilter("В планах", "2"),
        SearchFilter("Брошено", "3"),
        SearchFilter("Прочитано", "4"),
        SearchFilter("Любимые", "5")
    )
    companion object {
        const val PREFIX_SLUG_SEARCH = "slug:"
        private const val SERVER_PREF = "MangaLibImageServer"
        private const val SERVER_PREF_Title = "Сервер изображений"

        private const val SORTING_PREF = "MangaLibSorting"
        private const val SORTING_PREF_Title = "Способ выбора переводчиков"

        private const val isScan_USER = "ScanlatorUsername"
        private const val isScan_USER_Title = "Альтернативный переводчик"

        private const val TRANSLATORS_TITLE = "Чёрный список переводчиков\n(для красоты через «/» или с новой строки)"
        private const val TRANSLATORS_DEFAULT = ""

        private const val DOMAIN_PREF = "MangaLibDomain"
        private const val DOMAIN_PREF_Title = "Выбор домена"

        private const val LANGUAGE_PREF = "MangaLibTitleLanguage"
        private const val LANGUAGE_PREF_Title = "Выбор языка на обложке"

        private const val COVER_URL = "https://staticlib.me"
    }

    private var server: String? = preferences.getString(SERVER_PREF, null)
    private var isEng: String? = preferences.getString(LANGUAGE_PREF, "eng")
    private var groupTranslates: String = preferences.getString(TRANSLATORS_TITLE, TRANSLATORS_DEFAULT)!!
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverPref = ListPreference(screen.context).apply {
            key = SERVER_PREF
            title = SERVER_PREF_Title
            entries = arrayOf("Основной", "Второй (тестовый)", "Третий (эконом трафика)", "Авто")
            entryValues = arrayOf("secondary", "fourth", "compress", "auto")
            summary = "%s"
            setDefaultValue("auto")
            setOnPreferenceChangeListener { _, newValue ->
                server = newValue.toString()
                true
            }
        }

        val sortingPref = ListPreference(screen.context).apply {
            key = SORTING_PREF
            title = SORTING_PREF_Title
            entries = arrayOf(
                "Полный список (без повторных переводов)", "Все переводы (друг за другом)"
            )
            entryValues = arrayOf("ms_mixing", "ms_combining")
            summary = "%s"
            setDefaultValue("ms_mixing")
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                preferences.edit().putString(SORTING_PREF, selected).commit()
            }
        }
        val scanlatorUsername = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = isScan_USER
            title = isScan_USER_Title
            summary = "Отображает Ник переводчика если Группа не указана явно."
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit().putBoolean(key, checkValue).commit()
            }
        }
        val domainPref = ListPreference(screen.context).apply {
            key = DOMAIN_PREF
            title = DOMAIN_PREF_Title
            entries = arrayOf("Основной (mangalib.me)", "Зеркало (mangalib.org)")
            entryValues = arrayOf(baseOrig, baseMirr)
            summary = "%s"
            setDefaultValue(baseOrig)
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(DOMAIN_PREF, newValue as String).commit()
                    val warning = "Для смены домена необходимо перезапустить приложение с полной остановкой."
                    Toast.makeText(screen.context, warning, Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        val titleLanguagePref = ListPreference(screen.context).apply {
            key = LANGUAGE_PREF
            title = LANGUAGE_PREF_Title
            entries = arrayOf("Английский", "Русский")
            entryValues = arrayOf("eng", "rus")
            summary = "%s"
            setDefaultValue("eng")
            setOnPreferenceChangeListener { _, newValue ->
                val titleLanguage = preferences.edit().putString(LANGUAGE_PREF, newValue as String).commit()
                val warning = "Если язык обложки не изменился очистите базу данных в приложении (Настройки -> Дополнительно -> Очистить базу данных)"
                Toast.makeText(screen.context, warning, Toast.LENGTH_LONG).show()
                titleLanguage
            }
        }

        screen.addPreference(domainPref)
        screen.addPreference(serverPref)
        screen.addPreference(sortingPref)
        screen.addPreference(screen.editTextPreference(TRANSLATORS_TITLE, TRANSLATORS_DEFAULT, groupTranslates))
        screen.addPreference(scanlatorUsername)
        screen.addPreference(titleLanguagePref)
    }
    private fun PreferenceScreen.editTextPreference(title: String, default: String, value: String): androidx.preference.EditTextPreference {
        return androidx.preference.EditTextPreference(context).apply {
            key = title
            this.title = title
            summary = value.replace("/", "\n")
            this.setDefaultValue(default)
            dialogTitle = title
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(context, "Для обновления списка необходимо перезапустить приложение с полной остановкой.", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
    }
}
