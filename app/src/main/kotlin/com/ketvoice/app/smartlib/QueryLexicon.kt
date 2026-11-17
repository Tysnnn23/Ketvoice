package com.ketvoice.app.smartlib

/**
 * The words of the eleven interface languages (and the usual English ones) that a free-text
 * search may use for a style, a language, an ambiance or a tempo. Written in groups separated by
 * `|`; each group is folded with [SmartText.fold] when it is read, so accents may be written.
 */
internal object QueryLexicon {
    private val genres = listOf(
        "g:Rap" to "rap|raps|hip hop|hiphop|hip-hop|trap|drill|rappeur|rappeurs|рэп|хип-хоп|хип хоп|ラップ|ヒップホップ|说唱|嘻哈|랩|힙합|रैप|हिप हॉप",
        "g:Pop" to "pop|поп|ポップ|流行|流行乐|팝|पॉप",
        "g:RnB" to "rnb|r&b|r'n'b|r n b|rhythm and blues|ар-н-би|ритм-н-блюз|アールアンドビー|节奏布鲁斯|알앤비|आर एंड बी",
        "g:Rock" to "rock|рок|ロック|摇滚|록|रॉक",
        "g:Electro" to "electro|electronique|electronic|electronica|elettronica|elektronisch|eletronica|edm|house|techno|электроника|электро|エレクトロ|テクノ|电子|电音|일렉트로닉|일렉|इलेक्ट्रॉनिक",
        "g:Dance" to "dance|танцевальная|ダンス|舞曲|댄스|डांस",
        "g:Jazz" to "jazz|джаз|ジャズ|爵士|재즈|जैज़|जैज",
        "g:Classical" to "classique|classical|clasica|classica|klassik|klassische|classica|классическая|классика|クラシック|古典|古典音乐|클래식|शास्त्रीय",
        "g:Reggae" to "reggae|dancehall|регги|レゲエ|雷鬼|레게",
        "g:Latin" to "latino|latin|latina|reggaeton|salsa|bachata|латино|ラテン|拉丁|라틴",
        "g:Afro" to "afro|afrobeat|afrobeats|afro trap|zouk|coupe decale|афро|アフロ|非洲|아프로",
        "g:Metal" to "metal|metalcore|метал|メタル|金属|메탈",
        "g:Country" to "country|кантри|カントリー|乡村|컨트리",
        "g:Soul" to "soul|funk|соул|фанк|ソウル|ファンク|灵魂乐|放克|소울|펑크",
        "g:Folk" to "folk|фолк|フォーク|民谣|포크",
        "g:Blues" to "blues|блюз|ブルース|蓝调|블루스",
        "g:Chanson" to "chanson|chanson francaise|variete|variete francaise|шансон",
        "g:LoFi" to "lofi|lo fi|lo-fi|ローファイ|低保真|로파이",
        "g:Soundtrack" to "bande originale|soundtrack|ost|bso|colonna sonora|filmmusik|trilha sonora|саундтрек|サウンドトラック|サントラ|原声|配乐|사운드트랙|ost",
        "g:Indie" to "indie|inde|independant|инди|インディー|独立音乐|인디",
    )

    private val languages = listOf(
        "l:French" to "francais|francaise|francaises|fr|french|francophone|frances|francesa|francese|franzosisch|franzosische|frances|французский|французская|французские|французском|フランス|フランス語|法语|法国|프랑스|프랑스어|फ्रेंच|फ़्रेंच|फ्रांसीसी",
        "l:English" to "anglais|anglaise|english|ingles|inglesa|inglese|englisch|englische|английский|английская|英語|英语|英文|영어|अंग्रेज़ी|अंग्रेजी",
        "l:Spanish" to "espagnol|espagnole|espagnoles|espagnols|spanish|espanol|espanola|spagnolo|spagnola|spanisch|spanische|espanhol|испанский|испанская|スペイン|スペイン語|西班牙|西语|스페인|스페인어|स्पेनिश",
        "l:Italian" to "italien|italienne|italian|italiano|italiana|italiane|italiani|italienisch|italienische|итальянский|итальянская|イタリア|イタリア語|意大利|이탈리아|इतालवी",
        "l:German" to "allemand|allemande|german|aleman|alemana|tedesco|tedesca|deutsch|deutsche|deutscher|alemao|alema|немецкий|немецкая|ドイツ|ドイツ語|德语|德国|독일|독일어|जर्मन",
        "l:Portuguese" to "portugais|portugaise|portuguese|portugues|portuguesa|portoghese|portugiesisch|bresilien|bresilienne|brazilian|brasileiro|brasileira|brasileiros|brasileiras|португальский|ポルトガル|ポルトガル語|葡萄牙|포르투갈|पुर्तगाली",
        "l:Russian" to "russe|russian|ruso|rusa|russo|russa|russisch|russische|русский|русская|русские|русском|ロシア|ロシア語|俄语|俄罗斯|러시아|러시아어|रूसी",
        "l:Japanese" to "japonais|japonaise|japanese|japones|japonesa|giapponese|japanisch|japanische|japones|японский|японская|日本|日本語|日语|일본|일본어|जापानी",
        "l:Chinese" to "chinois|chinoise|chinese|chino|china|cinese|chinesisch|chinesische|chines|китайский|китайская|中国|中文|华语|中国語|중국|중국어|चीनी|mandopop|cantopop",
        "l:Korean" to "coreen|coreenne|korean|coreano|coreana|koreanisch|koreanische|корейский|корейская|韓国|韓国語|韩语|韩国|한국|한국어|कोरियाई",
        "l:Hindi" to "hindi|indien|indienne|indian|bollywood|хинди|ヒンディー|印地语|宝莱坞|힌디|हिंदी|हिन्दी|बॉलीवुड",
        "l:Arabic" to "arabe|arabic|arabo|araba|arabisch|arabische|арабский|アラビア|アラビア語|阿拉伯|아랍|아랍어|अरबी",
    )

    private val combined = listOf(
        "g:KPop,l:Korean" to "kpop|k pop|k-pop|케이팝|ケーポップ|韩流",
        "g:Pop,l:Japanese" to "jpop|j pop|j-pop|jポップ|日系流行",
        "g:Pop,l:Chinese" to "cpop|c pop|c-pop",
        "g:Rap,l:French" to "rap fr|rapfr|rap francais|rap francophone",
        "g:Chanson,l:French" to "chanson francaise",
    )

    private val moods = listOf(
        "m:Calm" to "calme|calmes|doux|douce|douces|tranquille|tranquilles|relax|relaxant|relaxante|apaisant|apaisante|chill|chill out|chillout|detente|zen|posé|pose|calm|soft|relaxing|chilled|mellow|peaceful|soothing|tranquilo|tranquila|relajante|suave|calmo|calma|rilassante|rilassanti|tranquillo|ruhig|ruhige|ruhiger|ruhigen|entspannt|entspannend|sanft|sanfte|relaxante|спокойный|спокойная|спокойные|расслабляющий|расслабляющая|чилл|落ち着いた|穏やか|チル|癒し|安静|放松|轻松|舒缓|잔잔한|편안한|차분한|शांत|सुकून|आरामदायक",
        "m:Energy" to "energique|energiques|energie|pechu|pechue|dynamique|dynamiques|motivant|motivante|sport|muscu|workout|gym|energetic|energy|hype|pump|enerjico|energico|energica|energia|motivacion|motivazione|energisch|kraftvoll|energiegeladen|animado|animada|энергичный|энергичная|энергичные|бодрый|бодрая|エネルギッシュ|元気|动感|活力|劲爆|运动|신나는|에너지|운동|ऊर्जावान|जोशीला|वर्कआउट",
        "m:Party" to "soiree|fete|party|danser|dansant|dansante|club|ambiance|fiesta|bailar|festa|ballare|feier|party|tanzen|danca|вечеринка|вечеринки|тусовка|パーティー|パーティ|派对|聚会|파티|पार्टी",
        "m:Focus" to "concentration|travail|travailler|etude|etudes|etudier|reviser|revisions|focus|study|studying|concentracion|estudiar|estudio|concentrazione|studiare|konzentration|konzentrieren|lernen|arbeiten|foco|estudar|concentracao|концентрация|учеба|учёба|работы|集中|勉強|作業用|专注|学习|工作|집중|공부|पढ़ाई|ध्यान|फोकस",
        "m:Melancholy" to "triste|tristes|melancolique|melancoliques|melancolie|nostalgique|nostalgie|deprime|sad|melancholic|melancholy|heartbreak|depressing|melancolico|melancolica|tristeza|malinconico|malinconica|malinconiche|malinconici|tristi|malinconia|traurig|traurige|melancholisch|грустный|грустная|грустные|печальный|печальная|悲しい|切ない|泣ける|伤感|悲伤|难过|슬픈|우울한|उदास|दुखी|दर्द भरे",
        "m:Happy" to "joyeux|joyeuse|joyeuses|heureux|heureuse|gai|gaie|feel good|feelgood|bonne humeur|happy|cheerful|joyful|alegre|alegres|feliz|allegro|allegra|felice|frohlich|frohliche|gute laune|весёлый|веселый|весёлая|веселая|радостный|明るい|ハッピー|快乐|开心|欢快|행복한|밝은|खुश|खुशी",
    )

    private val tempos = listOf(
        "t:Slow" to "lent|lente|lents|lentes|slow|lento|lenta|langsam|langsame|медленный|медленная|медленные|スロー|ゆっくり|慢歌|慢节奏|느린|धीमा|धीमे",
        "t:Fast" to "rapide|rapides|fast|rapido|rapida|veloce|veloci|schnell|schnelle|schneller|schnellen|быстрый|быстрая|быстрые|速い|アップテンポ|快歌|快节奏|빠른|तेज़|तेज",
    )

    /** Words that only join the others ("de", "the", "songs"…): left out of what remains of a search. */
    val fillers: Set<String> by lazy {
        ("de|des|du|d'|la|le|les|l'|un|une|et|en|avec|pour|sur|dans|musique|musiques|chanson|chansons|son|sons|titre|titres|morceau|morceaux|playlist|annees|annee|" +
            "the|of|from|and|with|for|in|some|music|songs|song|tracks|track|years|" +
            "el|los|las|del|y|con|para|musica|canciones|anos|" +
            "il|lo|gli|di|della|e|per|canzoni|anni|" +
            "der|die|das|den|und|mit|fur|musik|lieder|jahre|" +
            "o|os|as|do|da|dos|das|com|musicas|" +
            "песни|музыка|музыку|годов|годы|для|и|" +
            "の|音楽|曲|的|音乐|歌|歌曲|노래|음악|노래들|गाने|गीत|संगीत|के|की|का")
            .split('|').map(SmartText::fold).toSet()
    }

    /** Every word with what it means, longest first so that "chanson francaise" wins over "chanson". */
    val entries: List<Pair<String, List<QueryFilter>>> by lazy {
        (genres + languages + combined + moods + tempos)
            .flatMap { (target, words) -> words.split('|').map { SmartText.words(it) to filtersOf(target) } }
            .filter { it.first.isNotEmpty() }
            .distinctBy { it.first }
            .sortedByDescending { it.first.length }
    }

    /** Every word of the lexicon, to tell an artist's name from a word of the lexicon. */
    val words: Set<String> by lazy { entries.mapTo(HashSet()) { it.first } }

    private fun filtersOf(target: String): List<QueryFilter> = target.split(',').mapNotNull(::filterOf)

    private fun filterOf(code: String): QueryFilter? {
        val name = code.substringAfter(':')
        return when (code.substringBefore(':')) {
            "g" -> Genre.entries.firstOrNull { it.name == name }?.let(::GenreFilter)
            "l" -> Lang.entries.firstOrNull { it.name == name }?.let(::LanguageFilter)
            "m" -> Mood.entries.firstOrNull { it.name == name }?.let(::MoodFilter)
            "t" -> Tempo.entries.firstOrNull { it.name == name }?.let(::TempoFilter)
            else -> null
        }
    }
}
