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

# Collect whole concatenated descriptions, rather than translating fragments.
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
    '{0} titres': ['{0} songs', '{0} गाने', '{0}曲', '{0} 首歌曲', '{0} треков', '{0} brani', '{0} canciones', '{0} Songs', '{0} músicas', '{0}곡'],
    '{0} titre': ['{0} song', '{0} गाना', '{0}曲', '{0} 首歌曲', '{0} трек', '{0} brano', '{0} canción', '{0} Song', '{0} música', '{0}곡'],
    'Lecture depuis {0}': ['Playing from {0}', '{0} से चल रहा है', '{0}から再生中', '正在播放 {0}', 'Воспроизведение: {0}', 'In riproduzione da {0}', 'Reproduciendo desde {0}', 'Wiedergabe von {0}', 'Reproduzindo de {0}', '{0}에서 재생'],
    '{0} Mo': ['{0} MB'] * 10,
}

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
    for message in dict.fromkeys(MESSAGES):
        if message in REVIEWED:
            table[message] = REVIEWED[message][index]
        elif message not in table:
            table[message] = translate(message, language)
    ASSET.write_text(json.dumps(CATALOG, ensure_ascii=False, indent=1) + '\n', encoding='utf-8')
    print(language, len(table), flush=True)
