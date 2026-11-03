package com.freetv.app

data class Channel(
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val referrer: String? = null,
    val userAgent: String? = null
)

data class Source(val name: String, val url: String)

/** Hands the visible channel list to the player so up/down zapping follows what you saw. */
object ChannelStore {
    var playlist: List<Channel> = emptyList()
    var index: Int = 0
}

/**
 * Free, community-maintained playlists of publicly available channels (iptv-org).
 * You can also point the app at any other M3U URL ("Custom playlist").
 */
object Sources {
    private const val BASE = "https://iptv-org.github.io/iptv"

    val ALL = Source("All channels", "$BASE/index.m3u")

    private fun parse(data: String, folder: String): List<Source> =
        data.trim().lines().mapNotNull { line ->
            val parts = line.trim().split(' ', limit = 2)
            if (parts.size == 2) Source(parts[1], "$BASE/$folder/${parts[0]}.m3u") else null
        }

    val categories: List<Source> = parse(
        """
        news News
        sports Sports
        movies Movies
        series Series
        entertainment Entertainment
        music Music
        kids Kids
        animation Animation
        documentary Documentary
        comedy Comedy
        family Family
        general General
        culture Culture
        education Education
        science Science
        lifestyle Lifestyle
        cooking Cooking
        travel Travel
        outdoor Outdoor
        weather Weather
        business Business
        religious Religious
        public Public
        legislative Legislative
        shop Shopping
        relax Relax
        interactive Interactive
        auto Auto
        classic Classic
        """,
        "categories"
    )

    val languages: List<Source> = parse(
        """
        eng English
        urd Urdu
        hin Hindi
        ara Arabic
        ben Bengali
        pan Punjabi
        fas Persian
        pus Pashto
        spa Spanish
        por Portuguese
        fra French
        deu German
        ita Italian
        nld Dutch
        pol Polish
        tur Turkish
        rus Russian
        ukr Ukrainian
        zho Chinese
        jpn Japanese
        kor Korean
        ind Indonesian
        tha Thai
        vie Vietnamese
        tam Tamil
        tel Telugu
        mal Malayalam
        mar Marathi
        """,
        "languages"
    )

    val countries: List<Source> = parse(
        """
        af Afghanistan
        al Albania
        dz Algeria
        ar Argentina
        am Armenia
        au Australia
        at Austria
        az Azerbaijan
        bh Bahrain
        bd Bangladesh
        by Belarus
        be Belgium
        bo Bolivia
        ba Bosnia and Herzegovina
        br Brazil
        bg Bulgaria
        kh Cambodia
        ca Canada
        cl Chile
        cn China
        co Colombia
        cr Costa Rica
        hr Croatia
        cu Cuba
        cy Cyprus
        cz Czech Republic
        dk Denmark
        do Dominican Republic
        ec Ecuador
        eg Egypt
        sv El Salvador
        ee Estonia
        et Ethiopia
        fi Finland
        fr France
        ge Georgia
        de Germany
        gh Ghana
        gr Greece
        gt Guatemala
        hn Honduras
        hk Hong Kong
        hu Hungary
        is Iceland
        in India
        id Indonesia
        ir Iran
        iq Iraq
        ie Ireland
        il Israel
        it Italy
        jp Japan
        jo Jordan
        kz Kazakhstan
        ke Kenya
        kw Kuwait
        lv Latvia
        lb Lebanon
        ly Libya
        lt Lithuania
        lu Luxembourg
        my Malaysia
        mt Malta
        mx Mexico
        ma Morocco
        np Nepal
        nl Netherlands
        nz New Zealand
        ng Nigeria
        mk North Macedonia
        no Norway
        om Oman
        pk Pakistan
        ps Palestine
        pa Panama
        py Paraguay
        pe Peru
        ph Philippines
        pl Poland
        pt Portugal
        qa Qatar
        ro Romania
        ru Russia
        sa Saudi Arabia
        rs Serbia
        sg Singapore
        sk Slovakia
        si Slovenia
        so Somalia
        za South Africa
        kr South Korea
        es Spain
        lk Sri Lanka
        sd Sudan
        se Sweden
        ch Switzerland
        sy Syria
        tw Taiwan
        th Thailand
        tn Tunisia
        tr Turkey
        ae United Arab Emirates
        ug Uganda
        ua Ukraine
        uk United Kingdom
        us United States
        uy Uruguay
        uz Uzbekistan
        ve Venezuela
        vn Vietnam
        ye Yemen
        zw Zimbabwe
        """,
        "countries"
    )
}
