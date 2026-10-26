"""Maintainer-only: extend the bundled UI catalog, never translate user data.

Existing reviewed translations are retained. Network requests contain only the
public messages listed here or concatenated UI literals from the source tree.
The Android app always reads its bundled asset and has no translation dependency.
"""
import json
import pathlib
import re
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / ('app/app' if (ROOT / 'app/app/src').exists() else 'app')
ASSET = APP / 'src/main/assets/translations.json'
SOURCE = APP / 'src/main/kotlin/com/ketvoice/app'
CATALOG = json.loads(ASSET.read_text(encoding='utf-8'))
MESSAGES = [
    "Modifier l'audio ou la pochette", 'Modifier le titre importé', 'Recharger le titre importé',
    'Modifications enregistrées', 'Enregistrer les modifications',
    "La version hors ligne est inchangée. Supprimez son téléchargement puis téléchargez-la à nouveau pour actualiser l'audio.",
    "Choisissez uniquement les fichiers à remplacer. Les paroles et l'identifiant du titre sont conservés. Vérifiez leur synchronisation si l'audio change.",
    'Seuls les titres importés peuvent être modifiés', 'Titre importé introuvable pour ce compte',
    'Ce titre a changé. Rouvrez sa modification.', 'Choisissez un nouvel audio ou une nouvelle pochette',
    'Champs de modification invalides', 'Métadonnées invalides', 'Pochette trop volumineuse ou vide (4 Mo maximum)',
    'Sonic Canvas', 'Fond réactif au signal audio réel, sans microphone. Désactivé avec les animations réduites.',
    'Activer les Canvas vidéo', 'Coupe les vidéos et leurs téléchargements. Sonic Canvas possède son propre réglage.',
    'Capsule hors ligne', 'Préparer une capsule hors ligne', 'Durée souhaitée', 'Budget des fichiers',
    "Préparez une réserve pour votre trajet. Favoris en priorité, puis historique et sélection choisie. Aucun fichier existant n'est supprimé ou remplacé.",
    "Inclure la sélection d'accueil", 'Voir la sélection', '{0} titres sélectionnés',
    'Durée et taille de la sélection sont estimées. Le budget est contrôlé sur les fichiers réellement écrits, hors cache partagé. Pochettes et paroles sont conservées si disponibles et si elles tiennent dans le budget. Les vidéos Canvas ne sont pas ajoutées à la capsule.',
    'Écoutez ou recherchez quelques titres, puis créez votre sélection.', '{0} / {1} audios prêts',
    '{0} paroles · {1} pochettes enregistrées', 'Gardez Ketvoice ouvert pendant la préparation.',
    'Écouter la capsule', 'Préparer la capsule', 'Remplacer la sélection', 'Arrêter',
    'Préparation de la capsule…', 'Préparation terminée. Seuls les fichiers réellement enregistrés sont indiqués prêts.',
    'Préparation arrêtée. Les téléchargements déjà enregistrés sont conservés.',
    "Préparation interrompue. Vérifiez le réseau et l'espace disponible.", 'Budget de la capsule atteint',
    'Couleurs de la pochette',
    "Accents du lecteur adaptés à l'album, avec contraste renforcé",
    'Profondeur de pochette', 'Mouvement léger au toucher ou au pointeur, sans modifier les gestes',
    'Trier', 'Récents', 'Titre A–Z', 'Artiste A–Z', 'Rechercher des titres',
    'Aucun téléchargement', "Pour écouter hors ligne, ouvrez le menu d'un titre puis choisissez Télécharger.",
    'Audio enregistré · finalisation…', 'Téléchargement en cours · {0} %',
    'Catalogue indisponible', 'Aucun titre dans cette collection.',
    "Ajoutez des titres avec l'étoile du lecteur pour les retrouver ici.",
    'Vos titres écoutés apparaîtront ici. Lancez une première écoute.',
    'Vérifiez votre connexion, puis réessayez.', 'Écoutez un titre pour commencer votre collection.',
    'Essayez un autre titre, artiste ou album parmi vos téléchargements.',
    "Essayez un titre plus court ou le nom de l'artiste. Vérifiez aussi votre connexion.",
    'Choisissez un titre puis utilisez Lire ensuite pour préparer la suite.', 'Ajouter des titres',
    'Canvas panoramique',
    'Optionnel : Canvas en fond assombri, pochette fixe pour éviter un double décodage',
    'Animations réduites', 'Atténue les mouvements décoratifs, sans désactiver le karaoké',
    'Lisibilité sans agrandir les boutons', 'Égaliseur', 'Traitement réel de la session audio',
    'Transfert sur le même Wi-Fi', 'Transférer sur le même Wi-Fi',
    'Découverte locale et confirmation sur le destinataire',
    "Activer l'égaliseur", "Lancez un titre pour initialiser l'effet audio.",
    "L'égaliseur natif est indisponible sur cet appareil.",
    'Traitement natif actif sur la session audio.', 'Égaliseur désactivé.',
    "Les gains sont adaptés aux bandes et à la plage réellement prises en charge par l'appareil.",
    'Plat', 'Graves', 'Voix', 'Brillance', 'Rock',
    'Reprendre la lecture ici ?', 'La lecture et la file seront reprises uniquement après votre accord.',
    'Accepter', 'Refuser', 'Découverte et réception locales',
    'Activez cette option sur les deux appareils, puis choisissez le destinataire. Aucun QR code ni compte supplémentaire.',
    "Aucun appareil trouvé. Gardez Ketvoice ouvert sur l'autre appareil et vérifiez l'isolation Wi-Fi.",
    "Transfère le catalogue en ligne, la position et jusqu'à 50 titres. Aucun cookie, jeton de compte, fichier local ou URL privée n'est partagé. Confirmation obligatoire sur le destinataire.",
    'Transfert Wi-Fi désactivé', 'Connectez les deux appareils au même Wi-Fi privé IPv4',
    'Appareils sur le même Wi-Fi · confirmation obligatoire', 'Annonce locale indisponible sur ce réseau',
    'Découverte indisponible · vérifiez les autorisations réseau',
    'Transfert local indisponible sur ce réseau', 'Seuls les titres du catalogue en ligne peuvent être transférés',
    "En attente de confirmation sur l'autre appareil…", "Reprise en cours sur l'autre appareil…",
    'Lecture transférée', 'Transfert interrompu ou refusé · la lecture locale est conservée',
    'Lecture reçue', 'Impossible de reprendre ce titre',
    'QUEST · CONFORT',
    'Garder la fenêtre éveillée', 'Pendant la lecture au premier plan uniquement',
    'Taille des paroles', 'Lisibilité à distance dans le casque',
    'Standard', 'Grand', 'Très grand', 'Minuterie', 'Pause dans {0} min',
    'Annuler la minuterie', 'Ce casque', 'Lire', 'Pochette',
    "Un lien de connexion à usage unique sera envoyé à cette adresse. Ouvrez-le depuis cet appareil pour vérifier automatiquement votre accès dans Ketvoice. Aucun cookie YouTube n'est envoyé au serveur de publication.",
    'Lien envoyé. Ouvrez-le depuis cet appareil pour revenir dans Ketvoice. Si besoin, collez-le ci-dessous.',
    'Lien reçu par email (ou code)', 'Lien invalide',
    'Lien expiré ou déjà utilisé. Demandez un nouveau lien.',
    'Importer un son', 'Importer des lyrics', 'Vérification du compte de publication',
    "Un lien de connexion à usage unique sera envoyé à cette adresse. Aucun cookie YouTube n'est envoyé au serveur de publication.",
    'Envoyer le lien de connexion', 'Code ou lien reçu par email', 'Vérifier mon accès',
    "Lien envoyé. Copiez le lien reçu par email et collez-le ci-dessous sans l'ouvrir, ou saisissez son code.",
    'Publication réussie', 'Ce contenu est maintenant public dans Ketvoice API.',
    'Publication publique · Compte vérifié', 'Choisir le fichier audio', 'Changer le fichier audio',
    'MP3, M4A, AAC, FLAC, WAV, OGG ou Opus · 25 Mo maximum',
    'Ajouter une pochette', 'Changer la pochette', 'Pochette facultative · JPEG, PNG, WebP · 4 Mo maximum',
    'Choisir les paroles TTML ou LRC', 'Changer les paroles',
    'TTML ou LRC · 1 Mo maximum · Les timings du fichier sont conservés',
    "Je possède les droits nécessaires et j'autorise la diffusion publique de ces fichiers.",
    'Publier pour tous les utilisateurs', 'Déconnecter la publication', 'Publication en cours · {0} %',
    '{0} lignes', 'PUBLICATIONS KETVOICE API',
    'Confirmez votre adresse par un lien de connexion à usage unique',
    'Publier un fichier audio, sa pochette et ses paroles pour tous',
    'Recherchez un titre puis ouvrez son menu ⋯ pour publier un TTML ou LRC',
    'Titre', 'Fichier vide', 'Fichier inaccessible', 'Fichier trop volumineux',
    'Format de fichier non pris en charge', 'Nom de fichier inaccessible',
    'Choisissez un fichier audio, pas une vidéo', 'Publication impossible',
    'Paroles trop volumineuses (1 Mo maximum)', 'Choisissez un fichier TTML ou LRC',
    'Aucune parole synchronisée valide dans ce fichier', 'Session invalide',
    'Ce compte ne peut pas publier', 'Connexion administrateur requise',
    'Session administrateur expirée', 'Service de publication indisponible',
    'Fichier TTML invalide', 'Fichier LRC invalide', 'Confirmez vos droits de publication',
    'Audio requis (25 Mo maximum)', 'Format audio non pris en charge',
    'Pochette trop volumineuse (4 Mo maximum)', 'Pochette JPEG, PNG ou WebP requise',
    '{0} titre', '{0} titres', '{0} album', '{0} albums', '{0} artiste', '{0} artistes',
    '{0} épisode', '{0} épisodes', '{0} téléchargement', '{0} téléchargements',
    '{0} hors ligne', '{0} titres disponibles hors ligne', '{0} source(s)',
    '{0} active(s) sur {1}', '{0} en cours ({1} %)', '{0} Mo',
    '{0} écoute(s) acceptée(s) par YouTube', '{0} · session validée',
    '{0} · interface complète', 'Aucun résultat pour « {0} »',
    '« {0} » est à jour.', 'Échec du test : {0}', 'Source « {0} » ajoutée et validée.',
    'Lecture depuis {0}', 'À suivre depuis : {0}', 'Options de {0}', 'Retirer {0}',
    'Syllabe par syllabe · {0} lignes', 'Mot par mot · {0} lignes',
    'Ligne par ligne · {0} lignes', 'Texte non synchronisé · {0} lignes',
    'Téléchargement de « {0} »…', '« {0} » téléchargé',
    'Options de lecture', 'Ouvrir le lecteur', 'Plein écran', 'Réduire les paroles',
    'Aucun téléchargement.', 'Dans le lecteur, menu ⋯ → Télécharger.',
    'Paroles enregistrées', 'Canvas enregistré', 'Préparation des paroles et du Canvas…',
    'ligne par ligne', 'mot par mot', 'syllabe par syllabe',
    'Syllabe par syllabe', 'Mot par mot', 'Ligne par ligne', 'Texte non synchronisé',
    'interface complète', 'manifeste invalide', 'Song lyrics',
    '« {0} » jouera ensuite',
    'Icône mise à jour — le lanceur peut mettre quelques secondes',
    "Rien ici pour l'instant.\nBranchement des sources à l'étape des URLs.",
]
MESSAGES += [
    'Glissez ⠿ pour réordonner', 'Actions de la playlist', 'Terminer', "Modifier l'ordre",
    'Exporter (.json)', 'Exporter (.m3u)', 'Supprimer la playlist', 'Tout lire ensuite',
    'Télécharger la playlist', 'Tous les titres sont téléchargés ✓', 'Téléchargements lancés',
    'La liste peut être incomplète : touchez un titre manquant pour le retrouver.',
    'Importer un fichier · .json / .m3u', 'Fichier de playlist illisible ou invalide',
    'Téléchargement supprimé', 'Se déconnecter de YouTube Music ?',
    "La session chiffrée de cet appareil est effacée. Vos playlists et téléchargements Ketvoice sont conservés.",
    'Pas encore de Replay', "Vos titres les plus écoutés apparaîtront ici dès que vous aurez écouté de la musique.",
    'Vos artistes', 'Vos titres les plus écoutés', 'écoutes comptées', 'titres écoutés', 'Replay',
    'Diagnostic des sources', 'Teste chaque source sur le titre en cours', 'Aucun titre en lecture', 'Tester',
    'Diagnostic des sources de paroles',
    "Lisez un titre puis relancez le test : chaque source est interrogée sur ce titre.",
    'Test en cours…', "Le test n'a pas abouti. Vérifiez la connexion.",
    'lignes · syllabes natives', 'lignes · mots synchronisés', 'lignes · lignes synchronisées', 'lignes · texte simple',
    'AutoMix', 'Transitions fluides entre les morceaux · fondu analysé sur le rythme',
    "Analyse du rythme et fondu réel sur deux flux audio. Si l'analyse échoue, un fondu simple prend le relais.",
    'Fondu maximal', 'Choisissez la durée maximale des transitions', 'Automatique', 'Personnalisé',
    'En mode automatique, AutoMix ajuste la durée.', 'Performance',
    "Plus le niveau est élevé, plus l'analyse sollicite le processeur",
    'Basse', 'Moyenne', 'Standard', 'Élevée', 'Adaptée aux capacités de votre appareil.',
    'CPU minimal, fondu simple', 'Analyse légère', 'Équilibre qualité/performance',
    'Analyse approfondie et alignement des rythmes',
    "Le mode Basse privilégie la batterie. Le rythme est synchronisé lorsque l'analyse est suffisamment fiable, sans accélérer les morceaux.",
]

LITERAL = r'"(?:[^"\\]|\\.)*"'
CHAIN = re.compile(LITERAL + r'(?:\s*\+\s*' + LITERAL + r')+')
for path in SOURCE.rglob('*.kt'):
    if '/ui/' not in path.as_posix() and path.name not in ('MainActivity.kt', 'YouTubeLoginActivity.kt'):
        continue
    for match in CHAIN.finditer(path.read_text(encoding='utf-8')):
        parts = re.findall(LITERAL, match.group())
        if any('$' in part for part in parts):
            continue
        try:
            message = ''.join(json.loads(part) for part in parts)
        except (ValueError, json.JSONDecodeError):
            continue
        if len(message) > 25 and ' ' in message and '://' not in message:
            MESSAGES.append(message)

REVIEWED = {
    'Sonic Canvas': ['Sonic Canvas'] * 10,
    'Capsule hors ligne': ['Offline capsule', 'ऑफ़लाइन कैप्सूल', 'オフラインカプセル', '离线胶囊', 'Офлайн-капсула', 'Capsula offline', 'Cápsula sin conexión', 'Offline-Kapsel', 'Cápsula offline', '오프라인 캡슐'],
    "Modifier l'audio ou la pochette": ['Replace audio or artwork', 'ऑडियो या एल्बम कवर बदलें', '音声またはアルバムアートを変更', '替换音频或封面', 'Заменить аудио или обложку', 'Sostituisci audio o copertina', 'Reemplazar audio o carátula', 'Audio oder Albumcover ersetzen', 'Substituir áudio ou capa', '오디오 또는 앨범 아트 교체'],
    'Activer les Canvas vidéo': ['Enable video Canvases', 'वीडियो Canvas सक्षम करें', '動画Canvasを有効にする', '启用视频 Canvas', 'Включить видео Canvas', 'Attiva i Canvas video', 'Activar Canvas de vídeo', 'Video-Canvas aktivieren', 'Ativar Canvas de vídeo', '동영상 Canvas 활성화'],
    'Couleurs de la pochette': ['Album artwork colors', 'एल्बम कवर के रंग', 'アルバムアートの色', '专辑封面配色', 'Цвета обложки альбома', 'Colori della copertina', 'Colores de la carátula', 'Albumcover-Farben', 'Cores da capa do álbum', '앨범 아트 색상'],
    'Profondeur de pochette': ['Artwork depth', 'एल्बम कवर की गहराई', 'アルバムアートの奥行き', '封面景深', 'Глубина обложки', 'Profondità della copertina', 'Profundidad de la carátula', 'Albumcover-Tiefe', 'Profundidade da capa', '앨범 아트 깊이'],
    'Audio enregistré · finalisation…': ['Audio saved · finishing…', 'ऑडियो सहेजा गया · पूरा किया जा रहा है…', '音声を保存済み · 仕上げ中…', '音频已保存 · 正在完成…', 'Аудио сохранено · завершение…', 'Audio salvato · completamento…', 'Audio guardado · finalizando…', 'Audio gespeichert · wird abgeschlossen…', 'Áudio salvo · finalizando…', '오디오 저장됨 · 마무리 중…'],
    'Lire ensuite': ['Play next', 'अगला चलाएँ', '次に再生', '下一首播放', 'Воспроизвести следующим', 'Riproduci dopo', 'Reproducir a continuación', 'Als Nächstes abspielen', 'Reproduzir a seguir', '다음에 재생'],
    'Choisissez un titre puis utilisez Lire ensuite pour préparer la suite.': ['Choose a track, then use Play next to add it to the queue.', 'कोई ट्रैक चुनें, फिर उसे कतार में जोड़ने के लिए अगला चलाएँ का उपयोग करें।', '曲を選び、「次に再生」でキューに追加してください。', '选择曲目，然后使用“下一首播放”将其加入队列。', 'Выберите трек и нажмите «Воспроизвести следующим», чтобы добавить его в очередь.', 'Scegli un brano e usa Riproduci dopo per aggiungerlo alla coda.', 'Elige una pista y usa Reproducir a continuación para añadirla a la cola.', 'Wählen Sie einen Titel und fügen Sie ihn mit „Als Nächstes abspielen“ zur Warteschlange hinzu.', 'Escolha uma faixa e use Reproduzir a seguir para adicioná-la à fila.', '트랙을 선택한 후 다음에 재생을 눌러 대기열에 추가하세요.'],
    "Reprendre la lecture ici ?": ["Resume playback here?", "यहाँ प्लेबैक फिर से शुरू करें?", "ここで再生を再開しますか？", "在这里继续播放？", "Возобновить воспроизведение здесь?", "Riprendere la riproduzione qui?", "¿Reanudar la reproducción aquí?", "Wiedergabe hier fortsetzen?", "Retomar a reprodução aqui?", "여기에서 재생을 재개할까요?"],
    "La lecture et la file seront reprises uniquement après votre accord.": ["Playback and the queue will resume only after your approval.", "आपकी स्वीकृति के बाद ही प्लेबैक और कतार फिर से शुरू होंगे।", "再生とキューは、あなたが承認した後にのみ再開されます。", "只有在您同意后才会恢复播放和队列。", "Воспроизведение и очередь возобновятся только после вашего согласия.", "Riproduzione e coda riprenderanno solo dopo la tua approvazione.", "La reproducción y la cola se reanudarán solo tras tu aprobación.", "Wiedergabe und Warteschlange werden erst nach Ihrer Zustimmung fortgesetzt.", "A reprodução e a fila só serão retomadas após a sua aprovação.", "재생과 대기열은 승인 후에만 재개됩니다."],
    "Lecture transférée": ["Playback transferred", "प्लेबैक स्थानांतरित हुआ", "再生を転送しました", "播放已转移", "Воспроизведение передано", "Riproduzione trasferita", "Reproducción transferida", "Wiedergabe übertragen", "Reprodução transferida", "재생이 전송되었습니다"],
    "Lecture reçue": ["Playback received", "प्लेबैक प्राप्त हुआ", "再生を受信しました", "已接收播放", "Воспроизведение получено", "Riproduzione ricevuta", "Reproducción recibida", "Wiedergabe empfangen", "Reprodução recebida", "재생을 받았습니다"],
    "Transfert interrompu ou refusé · la lecture locale est conservée": ["Transfer interrupted or refused · local playback is kept", "स्थानांतरण बाधित या अस्वीकृत · स्थानीय प्लेबैक बना रहता है", "転送は中断または拒否されました · ローカル再生は維持されます", "传输已中断或被拒绝 · 本地播放保留", "Передача прервана или отклонена · местное воспроизведение сохранено", "Trasferimento interrotto o rifiutato · la riproduzione locale resta attiva", "Transferencia interrumpida o rechazada · la reproducción local se mantiene", "Übertragung unterbrochen oder abgelehnt · lokale Wiedergabe bleibt erhalten", "Transferência interrompida ou recusada · a reprodução local é mantida", "전송이 중단되었거나 거부되었습니다 · 로컬 재생은 유지됩니다"],
    "Reprise en cours sur l'autre appareil…": ["Resuming on the other device…", "दूसरे डिवाइस पर फिर से शुरू हो रहा है…", "もう一方のデバイスで再開しています…", "正在另一台设备上继续播放…", "Возобновление на другом устройстве…", "Ripresa sull'altro dispositivo…", "Reanudando en el otro dispositivo…", "Wird auf dem anderen Gerät fortgesetzt…", "Retomando no outro dispositivo…", "다른 기기에서 재개하는 중…"],
    "En attente de confirmation sur l'autre appareil…": ["Waiting for confirmation on the other device…", "दूसरे डिवाइस पर पुष्टि की प्रतीक्षा…", "もう一方のデバイスでの確認を待っています…", "正在等待另一台设备确认…", "Ожидание подтверждения на другом устройстве…", "In attesa di conferma sull'altro dispositivo…", "Esperando confirmación en el otro dispositivo…", "Warten auf Bestätigung auf dem anderen Gerät…", "Aguardando confirmação no outro dispositivo…", "다른 기기에서 확인을 기다리는 중…"],
    "Appareils sur le même Wi-Fi · confirmation obligatoire": ["Devices on the same Wi-Fi · confirmation required", "एक ही Wi-Fi पर डिवाइस · पुष्टि आवश्यक", "同じ Wi-Fi のデバイス · 確認が必要です", "同一 Wi-Fi 上的设备 · 需要确认", "Устройства в одной сети Wi-Fi · требуется подтверждение", "Dispositivi sulla stessa rete Wi-Fi · conferma richiesta", "Dispositivos en el mismo Wi-Fi · se requiere confirmación", "Geräte im selben WLAN · Bestätigung erforderlich", "Dispositivos no mesmo Wi-Fi · confirmação necessária", "동일한 Wi-Fi의 기기 · 확인 필요"],
    "Impossible de reprendre ce titre": ["Cannot resume this track", "यह ट्रैक फिर से शुरू नहीं हो सकता", "このトラックを再開できません", "无法继续播放此曲目", "Не удалось возобновить этот трек", "Impossibile riprendere questo brano", "No se puede reanudar esta pista", "Dieser Titel kann nicht fortgesetzt werden", "Não é possível retomar esta faixa", "이 트랙을 이어서 재생할 수 없습니다"],
    "Seuls les titres du catalogue en ligne peuvent être transférés": ["Only online catalog tracks can be transferred", "केवल ऑनलाइन कैटलॉग के ट्रैक स्थानांतरित किए जा सकते हैं", "オンラインカタログのトラックのみ転送できます", "仅可传输在线目录中的曲目", "Передавать можно только треки онлайн-каталога", "È possibile trasferire solo brani del catalogo online", "Solo se pueden transferir pistas del catálogo en línea", "Nur Titel aus dem Online-Katalog können übertragen werden", "Apenas faixas do catálogo online podem ser transferidas", "온라인 카탈로그의 트랙만 전송할 수 있습니다"],
    'Plat': ['Flat', 'सपाट', 'フラット', '平直', 'Ровный', 'Piatto', 'Plano', 'Neutral', 'Plano', '플랫'],
    'Graves': ['Bass', 'बास', '低音', '低音', 'Бас', 'Bassi', 'Graves', 'Bass', 'Graves', '저음'],
    'Voix': ['Voice', 'आवाज़', 'ボーカル', '人声', 'Голос', 'Voce', 'Voz', 'Stimme', 'Voz', '보컬'],
    'Brillance': ['Bright', 'चमक', '明瞭', '明亮', 'Яркость', 'Brillante', 'Brillante', 'Brillant', 'Brilho', '선명'],
    'Pause dans {0} min': ['Pause in {0} min', '{0} मिनट में रोकें', '{0}分後に一時停止', '{0} 分钟后暂停', 'Пауза через {0} мин', 'Pausa tra {0} min', 'Pausar en {0} min', 'Pause in {0} Min.', 'Pausar em {0} min', '{0}분 후 일시정지'],
    'Ce casque': ['This headset', 'यह हेडसेट', 'このヘッドセット', '此头显', 'Эта гарнитура', 'Questo visore', 'Este visor', 'Dieses Headset', 'Este headset', '이 헤드셋'],
    'Lire': ['Play', 'चलाएँ', '再生', '播放', 'Воспроизвести', 'Riproduci', 'Reproducir', 'Abspielen', 'Reproduzir', '재생'],
    'Pochette': ['Artwork', 'एल्बम कवर', 'アートワーク', '封面', 'Обложка', 'Copertina', 'Carátula', 'Cover', 'Capa', '앨범 아트'],
    '{0} titres': ['{0} songs', '{0} गाने', '{0}曲', '{0} 首歌曲', '{0} треков', '{0} brani', '{0} canciones', '{0} Songs', '{0} músicas', '{0}곡'],
    '{0} titre': ['{0} song', '{0} गाना', '{0}曲', '{0} 首歌曲', '{0} трек', '{0} brano', '{0} canción', '{0} Song', '{0} música', '{0}곡'],
    'Lecture depuis {0}': ['Playing from {0}', '{0} से चल रहा है', '{0}から再生中', '正在播放 {0}', 'Воспроизведение: {0}', 'In riproduzione da {0}', 'Reproduciendo desde {0}', 'Wiedergabe von {0}', 'Reproduzindo de {0}', '{0}에서 재생'],
    '{0} Mo': ['{0} MB'] * 10,
}

extra = json.loads((ROOT / 'tools/content-feature-translations.json').read_text(encoding='utf-8'))
for language in CATALOG:
    assert len(extra['translations'][language]) == len(extra['keys'])
REVIEWED.update({message: [extra['translations'][language][index] for language in CATALOG]
                 for index, message in enumerate(extra['keys'])})

def translate(message, language):
    target = 'zh-CN' if language == 'zh' else language
    url = 'https://translate.googleapis.com/translate_a/single?' + urllib.parse.urlencode(
        {'client': 'gtx', 'sl': 'fr', 'tl': target, 'dt': 't', 'q': message})
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=40) as response:
                data = json.load(response)
            value = ''.join(part[0] for part in data[0] if part and part[0]).strip()
            if set(re.findall(r'\{\d+}', value)) != set(re.findall(r'\{\d+}', message)):
                raise ValueError('Translation changed message placeholders')
            return value
        except Exception:
            if attempt == 3:
                raise
            time.sleep(attempt + 1)

for index, (language, table) in enumerate(CATALOG.items()):

    for retired in ('Mode cinéma', 'Pochette et Canvas en grand, commandes toujours accessibles', 'KETVOICE · QUEST'):
        table.pop(retired, None)
    for message in dict.fromkeys(MESSAGES):
        if message in REVIEWED:
            table[message] = REVIEWED[message][index]
        elif message not in table:
            table[message] = translate(message, language)
    print(language, len(table), flush=True)

temporary = ASSET.with_suffix('.json.tmp')
temporary.write_text(json.dumps(CATALOG, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
temporary.replace(ASSET)
