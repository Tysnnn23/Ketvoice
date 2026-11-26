package com.ketvoice.app.ui.settings

/** A row of Settings as the search knows it: its French title and description (the source texts) and where it lives. */
internal data class SettingEntry(val title: String, val sub: String, val category: SettingsCategory, val shown: () -> Boolean = { true })

/**
 * « Recherche dans les réglages » (8.4): every row of every page of Settings, searchable from the top of Settings.
 * Titles and descriptions are matched as the listener reads them (in the interface language), without accents or
 * case; a result opens its page and points at its row. `SettingsSearchIndexTest` keeps this list in step with the
 * rows the pages really draw.
 */
internal object SettingsSearch {
    private val notQuest: () -> Boolean = { !com.ketvoice.app.BuildConfig.IS_QUEST }
    private val questOnly: () -> Boolean = { com.ketvoice.app.BuildConfig.IS_QUEST }
    private val liteOnly: () -> Boolean = { com.ketvoice.app.LiteTuning.lite }
    private val stems: () -> Boolean = { com.ketvoice.app.data.AppSettings.ketmixStems.value }

    private fun a(title: String, sub: String = "", shown: () -> Boolean = { true }) = SettingEntry(title, sub, SettingsCategory.Account, shown)
    private fun ap(title: String, sub: String = "", shown: () -> Boolean = { true }) = SettingEntry(title, sub, SettingsCategory.Appearance, shown)
    private fun p(title: String, sub: String = "", shown: () -> Boolean = { true }) = SettingEntry(title, sub, SettingsCategory.Playback, shown)
    private fun l(title: String, sub: String = "", shown: () -> Boolean = { true }) = SettingEntry(title, sub, SettingsCategory.Lyrics, shown)
    private fun c(title: String, sub: String = "") = SettingEntry(title, sub, SettingsCategory.Canvas)
    private fun d(title: String, sub: String = "") = SettingEntry(title, sub, SettingsCategory.Downloads)
    private fun st(title: String, sub: String = "") = SettingEntry(title, sub, SettingsCategory.Storage)
    private fun pz(title: String, sub: String = "") = SettingEntry(title, sub, SettingsCategory.Personalize)

    /** The rows, in the order of their pages. */
    val entries: List<SettingEntry> by lazy { fixed + personalize + com.ketvoice.app.data.DesignChoices.all.map {
        SettingEntry(it.title, "Revenir à la présentation précédente en désactivant cette option", SettingsCategory.Design)
    } + com.ketvoice.app.widget.WidgetPinning.widgets.map { SettingEntry(it.name, it.description, SettingsCategory.Appearance, notQuest) } }

    /** The rows of « Personnalisation » (2026-11-22). */
    private val personalize: List<SettingEntry> get() = listOf(
        pz("Partager le thème", "Un code court à envoyer ; il commence par KT1."),
        pz("Importer un thème", "Collez un code reçu d'un ami"),
        pz("Police"), pz("Taille du texte"), pz("Arrondis"), pz("Densité des listes"), pz("Police des paroles"),
        pz("Taille du texte des paroles"), pz("Alignement"), pz("Couleur des mots chantés"),
        pz("Intensité du flou"), pz("Mouvement des mots"))

    private val fixed: List<SettingEntry> get() = listOf(
        a("Compte YouTube Music", "Connexion web pour l'historique et l'accueil personnel"),
        a("Synchroniser les écoutes", "Une écoute n'est validée que si YouTube l'accepte"),
        a("Accueil personnalisé", "Remplace les sélections locales par votre accueil YouTube Music"),
        ap("Garder la fenêtre éveillée", "Pendant la lecture au premier plan uniquement", questOnly),
        ap("Langue de l'application", "Suit la langue du téléphone tant que tu n'en choisis pas une"),
        ap("Reflets selon l'inclinaison", "Le reflet du verre suit doucement l'inclinaison du téléphone", notQuest),
        ap("Verre teinté par la pochette", "La couleur dominante de la pochette colore légèrement le verre, sans réduire la lisibilité"),
        ap("Couleur de la pochette", "La couleur d'accent de toute l'interface suit la pochette du titre en cours"),
        ap("Fond selon la pochette", "Le fond de toute l'application prend la couleur de ce qui est en écoute, comme une page d'album"),
        ap("Couleurs de la pochette", "Accents du lecteur adaptés à l'album, avec contraste renforcé"),
        ap("Stats info", "Informations audio réelles, uniquement dans le lecteur"),
        ap("Photos d'artiste HD", "Portrait ou fond haute résolution quand il existe. Aucune photo de remplacement inventée."),
        ap("Signatures et logos d'artistes", "Signature authentique (Wikidata), sinon archive publique de signatures, sinon logo transparent. Le nom est envoyé à ces services uniquement si activé."),
        ap("Fluidité 120 Hz", "Demande le mode 120 Hz natif, sans changer la résolution. Consomme davantage de batterie."),
        ap("Personnalisation", "Couleurs, police, arrondis et paroles"),
        ap("DESIGN KETVOICE", "Chaque finition peut être retirée séparément, sans modifier vos playlists ni votre musique."),
        ap("Canvas sur les pages d'album", "Affiche la vidéo de l'album à la place de la pochette, en plein cadre. Indépendant du Canvas du lecteur."),
        ap("En-tête des albums et playlists", "Sans Canvas, la pochette centrée s'affiche"),
        ap("Fond animé du lecteur", "Dégradé lent aux couleurs de la pochette derrière le lecteur, qui suit doucement la musique"),
        ap("Retours haptiques", "Légère vibration sur le verre, les onglets et la barre de lecture", notQuest),
        ap("Pochette vivante", "Dans le lecteur, la pochette pulse très légèrement au rythme des basses"),
        ap("Profondeur de pochette", "Mouvement léger au toucher ou au pointeur, sans modifier les gestes"),
        ap("Canvas panoramique", "Optionnel : Canvas en fond assombri, pochette fixe pour éviter un double décodage"),
        ap("Animations réduites", "Atténue les mouvements décoratifs, sans désactiver le karaoké"),
        ap("Taille des paroles", "Lisibilité sans agrandir les boutons"),
        ap("Thème", "S'applique immédiatement à toute l'app"),
        ap("Fond noir", "En mode sombre, les pages restent noir pur, même sous la couleur d'une pochette (écrans OLED)"),
        ap("Couleur d'accent", "Barre de lecture, scrub, boutons et progression"),
        ap("Verre liquide", "Flou, réfraction et reflets sur l'interface"),
        ap("Style du verre", "La matière du verre et ses animations"),
        ap("Mode performance", "Garde les effets visibles et allège ceux qu'on remarque à peine : verre sans réfraction, paroles sans flou, fond animé à cadence réduite, images sans fondu"),
        ap("Mode économie auto", "Batterie à 20 % ou moins hors charge, ou appareil très chaud : le mode performance s'active le temps qu'il faut"),
        ap("Flou dynamique", "Réfraction en temps réel derrière les panneaux"),
        ap("Icône de l'app", "Change réellement l'icône du lanceur"),
        p("Normaliser le volume", "Tous les titres à un volume perçu proche, sans saut d'une chanson à l'autre"),
        p("Lecture en arrière-plan", "Si la musique se coupe quand Ketvoice n'est pas à l'écran, laissez Android la jouer sans restriction de batterie.", notQuest),
        p("Nouvelles sorties de vos artistes", "Une notification quand un artiste que vous suivez sort un album, un single ou un EP. Vérifié deux fois par jour, au plus trois notifications à la fois."),
        p("Reprendre automatiquement la lecture après un arrêt par le système", "Si Android ou le gestionnaire de batterie arrête Ketvoice pendant l'écoute, la musique reprend au même titre et au même endroit dans les minutes qui suivent. Jamais après une pause ou un arrêt de votre part."),
        p("Journal de lecture", "Ce qui a arrêté la musique : réseau perdu, autre app, fermeture par Android. Reste sur le téléphone."),
        p("Aléatoire avec moins de répétitions", "Le mode aléatoire garde pour plus tard les titres écoutés récemment et n'enchaîne pas deux titres du même artiste quand il peut l'éviter."),
        p("Partager le titre en cours sur le site", "Le compteur d'auditeurs reste anonyme ; le titre, l'artiste et la pochette ne sont envoyés qu'avec ton accord"),
        p("Qualité progressive", "Activé : les sources sont interrogées en parallèle, le premier flux audio réellement valide démarre, puis une meilleure qualité prend le relais sans couper la lecture. Désactivé : la première source disponible dans l'ordre des Sources est utilisée."),
        p("Bit-Perfect", "Envoie le PCM décodé au mixeur sans rééchantillonnage ni traitement de l'app, lorsque Android et la sortie le permettent. Égaliseur, réverbération et ignorance des silences sont alors désactivés."),
        p("Égaliseur", "Traitement réel de la session audio"),
        p("Ketogether", "Écouter ensemble, en même temps, avec une file partagée"),
        p("Transfert sur le même Wi-Fi", "Découverte locale et confirmation sur le destinataire"),
        p("Qualité en Wi-Fi", "Sans perte = FLAC, sinon MP3 rapide"),
        p("Qualité en données mobiles", "Appliquée quand le réseau est facturé"),
        p("Ignorer les silences", "Saute les passages silencieux (moteur audio)"),
        p("Ketmix", "La fin d'un titre se mêle au début du suivant, comme le ferait un DJ : tempos accordés, basses échangées, écho, filtres ou réverbération selon les deux titres. Le chant n'est jamais coupé : une voix qui se termine passe en retrait sous le titre suivant."),
        p("Intensité du mix", "Longueur des transitions et écart de tempo accepté"),
        p("Analyse des titres", "Part du processeur du téléphone consacrée à l'écoute des titres avant un mix"),
        p("Style de transition", "Comment un titre passe au suivant"),
        p("Mixer aussi les albums", "Les titres d'un album écouté dans l'ordre sont mixés aussi, sauf si l'album n'a aucun blanc entre ses titres."),
        p("Vibrer à l'échange des basses", "Une petite vibration au moment où la basse passe d'un titre à l'autre, quand l'app est à l'écran."),
        p("Ketmix apprend de vos avis", KETMIX_LEARNS_SUB),
        p("Oublier ce que Ketmix a appris", "Ketmix apprend de vos avis"),
        p("Séparer les voix", "Pendant un mix, la voix du titre en cours s'arrête et le suivant continue sur son instrumental, puis sa voix le rejoint ; quand tempo et tonalité s'accordent, la voix du suivant chante sur l'instrumental de celui en cours. Avec le modèle de séparation, la voix est vraiment retirée ; sans lui, elle est seulement atténuée."),
        p("Calage précis du rythme", "Un petit modèle d'IA intégré trouve les temps et les mesures pour des mélanges mieux calés."),
        p("File intelligente", "En lecture aléatoire, le titre suivant est choisi parmi les prochains pour s'accorder au tempo et à la tonalité de celui en cours. Une file que vous avez ordonnée et un titre demandé « à la suite » ne bougent jamais."),
        p("Mixer quand je passe au suivant", "Activé : « Suivant » enchaîne avec un vrai mix. Désactivé : le titre suivant démarre tout de suite, avec un fondu très court ; les fins de titres restent mixées."),
        p("Mixer aussi un titre en boucle", "Quand un titre est répété, sa fin se mêle à son propre début, sur le temps, au lieu de repartir après un blanc."),
        p("Qualité de séparation", "Quel modèle sépare les titres", stems),
        p("Titres séparés", "Chaque titre n'est séparé qu'une fois ; ses mix suivants réutilisent la séparation. Toucher pour tout effacer.", stems),
        p("Séparer seulement en charge", "Les nouvelles séparations n'ont lieu que téléphone branché ; celles déjà faites servent toujours.", stems),
        p("Préparer les titres téléchargés", "Téléphone en charge, les titres téléchargés sont analysés (et leurs voix séparées si le modèle est là) avant même d'être joués : leurs mix sont prêts tout de suite."),
        l("Diagnostic des sources", "Teste chaque source sur le titre en cours"),
        l("Source des paroles", "Priorité : syllabes natives, mots, lignes, texte"),
        l("Ordre des sources de paroles", "Les sources sont essayées dans cet ordre : la première qui a des paroles gagne"),
        l("Mode des paroles", "Syllabes ou mots seulement si la source les fournit"),
        l("Paroles sur l'écran verrouillé", "La ligne chantée dans une notification silencieuse, sur l'écran verrouillé et dans le volet ; la suivante en dessous.", notQuest),
        l("Flou des paroles", "Les lignes éloignées de celle chantée s'estompent dans un léger flou."),
        l("Mode veille au chargement", "En charge et à l'horizontale pendant l'écoute : la pochette ou le Canvas, l'heure et les paroles en plein écran. Aussi proposé comme économiseur d'écran d'Android.", notQuest),
        l("Clé BetterLyrics (facultative)", "Cache public ; certaines recherches exigent une clé"),
        l("Instance Lyrica", "URL HTTPS de votre serveur Lyrica"),
        l("lrc.red", "Source intégrée · recherche de paroles par ISRC"),
        l("Langue d'origine (traduction)", "Utilisée par le service de secours MyMemory"),
        l("Langue de traduction", "Traduction réelle, sans timing par syllabe"),
        c("Sonic Canvas", "Fond réactif au signal audio réel, sans microphone. Désactivé avec les animations réduites."),
        c("Ketvoice API", "Canvas Spotify résolu et authentifié côté Supabase"),
        c("Activer les Canvas vidéo", "Coupe les vidéos et leurs téléchargements. Sonic Canvas possède son propre réglage."),
        c("Canvas en données mobiles", "Autorise le téléchargement des canvas hors Wi-Fi"),
        c("Précharger en Wi-Fi", "Charge à l'avance le Canvas de tes favoris et de tes playlists, en Wi-Fi seulement, application ouverte ou téléphone en charge."),
        c("Espace utilisé par les Canvas"),
        c("Limite du cache des Canvas", "Les moins récemment utilisés partent en premier, jamais ceux de tes téléchargements."),
        c("Vider le cache des Canvas", "Garde ceux de tes titres téléchargés"),
        c("Tester le canvas"),
        SettingEntry("Ketvoice API", "Recherche, streaming et téléchargements · Supabase", SettingsCategory.Sources),
        SettingEntry("Sources personnalisées", "URLs HTTPS terminées par .json · streaming et téléchargements", SettingsCategory.Sources),
        d("Favoris hors ligne automatiquement", "Un titre ajouté aux favoris se télécharge tout seul, pour l'écouter sans connexion"),
        d("Téléchargements en Wi-Fi uniquement", "Les transferts attendent une connexion Wi-Fi"),
        d("Playlists téléchargées à jour", "Une notification quand une playlist téléchargée reçoit de nouveaux titres : touchez-la pour télécharger seulement ceux-là"),
        d("Qualité de téléchargement", "Le fichier réellement obtenu, pas seulement le réglage"),
        d("Voir la liste", "Pistes enregistrées sur l'appareil"),
        d("Hors ligne intelligent", "Télécharge tout seul, en Wi-Fi, ce que tu écoutes le plus et tes nouveaux favoris."),
        d("Espace maximum", "Quand il manque de la place, les titres automatiques les moins écoutés partent en premier, jamais ceux que tu as téléchargés toi-même."),
        d("Seulement en charge", "Les téléchargements automatiques attendent que le téléphone soit branché."),
        d("Voir ce qui a été téléchargé automatiquement"),
        d("Supprimer les téléchargements automatiques", "Garde les titres que tu as téléchargés toi-même"),
        SettingEntry("Dossier de musique locale", "", SettingsCategory.LocalMusic),
        SettingEntry("Revenir à tout l'appareil", "Le scan MediaStore remplace le dossier choisi", SettingsCategory.LocalMusic),
        SettingEntry("Correspondances musicales", "Compare tes fichiers au catalogue et propose artiste, album et pochette. Rien ne change sans ton accord, et tes fichiers restent intacts.", SettingsCategory.LocalMusic),
        st("Cache des chansons utilisé", "Le cache accélère les relectures"),
        st("Sauvegarde personnelle", "Playlists, favoris et réglages"),
        st("Air Ket", "Tout transférer vers un autre téléphone, en Wi-Fi"),
        st("Vider le cache des chansons", "Les paroles et canvas téléchargés restent"),
        st("Vider le cache des images", "Pochettes et photos d'artistes re-téléchargées à la demande"),
        SettingEntry("Vérifier les mises à jour", "", SettingsCategory.About),
        SettingEntry("Version", "YouTube Music · Ketvoice API · canvas · paroles", SettingsCategory.About),
        SettingEntry("Ketvoice Lite", "", SettingsCategory.About, liteOnly),
        SettingEntry("Diagnostic intégré", "Note sur cet appareil les saccades, le démarrage, le délai avant le son, les chargements et la mémoire. Rien n'est envoyé, aucune donnée personnelle", SettingsCategory.About),
        SettingEntry("Partager le diagnostic", "Par le menu de partage, seulement quand vous le choisissez", SettingsCategory.About),
    ) + layout

    /** Personnalisation › Disposition (9.0, `LayoutCustomization.kt`). */
    private val layout: List<SettingEntry> get() = listOf(
        "Onglets à la carte" to "Les onglets de la barre et leur ordre ; la barre latérale des tablettes suit le même ordre",
        "Accueil modulable" to "Afficher, masquer et réordonner les sections de l'accueil",
        "Boutons du lecteur" to "La rangée de boutons sous la barre de lecture",
        "Boutons du mini-lecteur" to "À côté de lecture/pause, jusqu'à deux",
        "Taille de la pochette" to "Dans le lecteur, toujours bord à bord",
        "Canvas dans le lecteur" to "Désactivé, le lecteur montre la pochette. Les Canvas restent sur l'appareil et ailleurs dans l'app.",
        "Bibliothèque" to "Liste ou grille, et l'ordre de chaque section à l'ouverture",
        "Menu des titres" to "Ordre et éléments du menu d'un titre ; « Plus » reste à la fin",
        "Balayer vers la droite" to "Sur une ligne de titre",
        "Balayer vers la gauche" to "Sur une ligne de titre",
        "Rétablir la disposition" to "Onglets, accueil, lecteur, bibliothèque, menu et gestes d'origine",
    ).map { (title, sub) -> SettingEntry(title, sub, SettingsCategory.Personalize) }

    /** [text] without accents, apostrophes told apart or case ([SettingsMatch.fold]). */
    fun fold(text: String): String = SettingsMatch.fold(text)

    /**
     * The rows [query] finds, best first: every word of the query must be in the row's title or description as
     * [translate] gives them (the interface language). A title that starts with the query comes first.
     */
    fun search(query: String, entries: List<SettingEntry> = this.entries, translate: (String) -> String = com.ketvoice.app.data.AppText::t): List<SettingEntry> {
        val words = SettingsMatch.words(query)
        if (words.isEmpty()) return emptyList()
        return entries.filter { it.shown() }.mapNotNull { entry ->
            SettingsMatch.rank(query, words, translate(entry.title), if (entry.sub.isEmpty()) "" else translate(entry.sub))?.let { entry to it }
        }.sortedBy { it.second }.map { it.first }.distinctBy { it.title to it.category }
    }
}
