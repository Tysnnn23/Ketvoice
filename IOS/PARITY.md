# État du portage iPhone

Ce tableau distingue le code présent des fonctions réellement validées.
La compilation ne constitue pas une validation audio sur appareil.

| Fonction Android | État iPhone | Validation disponible |
| --- | --- | --- |
| Retour vers l'écran précédent | `NavigationStack`, fermeture du lecteur après retour à sa page principale | Compilation ; parcours interactifs à tester |
| Catalogue Ketvoice et publications | Mêmes API publiques, résolution HTTPS à la lecture | Health HTTP depuis Windows ; pas de lecture iPhone physique |
| Recherche YouTube Music | Pas encore portée | Aucune |
| Pages artistes / albums | Pas encore portées | Aucune |
| Lecteur et file | AVPlayer, précédent/suivant, aléatoire/répétition | Compilation ; audio non validé |
| Commandes système, casque, AirPlay | Code présent | Compilation ; casque/AirPlay physique non validés |
| Minuterie de sommeil | Code présent, annulation remplaçable | Compilation ; déclenchement réel non testé |
| Favoris | Sauvegarde locale atomique, identité par ID du titre | Tests Swift du modèle ; persistance UI à tester |
| Playlists locales | Création, renommage, ordre, suppression ; entrées à UUID | Tests Swift du modèle ; parcours UI à tester |
| Import public Spotify | Pas encore porté | Aucune |
| Téléchargements | Fichiers locaux, annulation, plafond 300 Mo, vérification de piste audio | Compilation ; téléchargement réel via iPhone non validé |
| Canvas | Boucle muette, arrêt à la pause/arrière-plan, réglage et réduction des animations | Compilation ; vidéo physique non validée |
| Paroles LRC | Temps, offsets, recherche temporelle par toucher | Tests Swift du parseur ; défilement en lecture non validé |
| TTML / karaoké mot-à-mot | Pas encore portés | Aucune |
| AutoMix / égaliseur / capsules | Pas encore portés ; aucun faux contrôle actif | Aucune |
| Publication propriétaire | Pas encore portée ; aucune écriture privilégiée iOS | Aucune |
| Liquid Glass natif | API SwiftUI natives, pas de flou simulé | Compilation SDK iOS 27 ; accueil examiné sur simulateur |
| Onze langues | Catalogues générés depuis Android ; français par défaut | Cohérence des catalogues ; libellés iOS complémentaires à traduire |
| Synchronisation Android/iPhone | Catalogue serveur partagé uniquement | Aucun partage des favoris, playlists ou réglages personnels |
| Transfert Wi-Fi mono-appareil | Pas encore porté | Aucune |
| Ketogether / écoute de groupe | Différé, non autorisé | Aucune |

Les corrections futures doivent être appliquées aux deux plateformes lorsque
leur fonctionnalité existe. Une absence de portage doit rester explicite ici,
dans `README.md` et dans les notes de version ; elle ne doit pas être remplacée
par un réglage sans effet ou présentée comme une fonction synchronisée.
