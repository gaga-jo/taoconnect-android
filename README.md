# TaoConnect — connecteur français pour Taobao

TaoConnect n’essaie pas de remplacer Taobao. Il s’installe comme un petit
connecteur Android : une activation ouvre l’application Taobao, puis une bulle
flottante traduit en français le chinois visible à l’écran.

## Ce que fait la version 0.4

- activation unique **Connecter et ouvrir Taobao** ;
- raccourci Android **Taobao FR** dans les réglages rapides ;
- ouverture automatique de l’application officielle Taobao ;
- bulle flottante et déplaçable `文 / FR` ;
- reconnaissance du chinois simplifié et traditionnel ;
- traduction chinois → français sur le téléphone après téléchargement initial ;
- glossaire français concis pour les commandes Taobao les plus fréquentes ;
- mode fluide activé depuis la bulle, avec actualisation adaptative après le défilement ;
- conservation de la traduction précédente pendant la préparation de la suivante ;
- cache en mémoire des traductions déjà obtenues et détection tolérante des écrans inchangés ;
- priorité aux boutons, actions et textes courts pour éviter de saturer l’écran ;
- détection des fenêtres centrales afin d’ignorer leur arrière-plan ;
- remplacement intégré pour les boutons et libellés courts, adapté à la couleur d’origine ;
- petites légendes reliées à leur source pour les phrases longues, sans couvrir les autres textes ;
- protection des prix, nombres, URL et références techniques ;
- six traductions prioritaires au maximum et zone réservée autour de la bulle ;
- superposition transparente sous la limite Android afin de laisser passer les gestes vers Taobao ;
- traduction ponctuelle également disponible depuis la notification ;
- arrêt immédiat depuis TaoConnect, la notification ou le raccourci rapide ;
- aucune sauvegarde des images capturées.

## Pourquoi un petit composant Android reste nécessaire

Android ne permet pas à un programme extérieur de modifier directement
l’interface interne de Taobao. Le connecteur doit donc être installé sous forme
d’un APK léger afin d’obtenir, avec l’accord de l’utilisateur, les autorisations
de capture et d’affichage superposé. Une fois configuré, l’écran principal de
TaoConnect ne sert presque plus : le raccourci **Taobao FR** lance le connecteur.

## Utilisation

1. Installer puis ouvrir TaoConnect une première fois.
2. Appuyer sur **Ajouter le raccourci “Taobao FR”**.
3. Toucher **Connecter et ouvrir Taobao**.
4. Autoriser l’affichage superposé et le partage d’écran Android.
5. Attendre le message indiquant que le modèle français est prêt.
6. Dans Taobao, toucher la bulle `文 / FR` pour activer le mode fluide.
7. Faire défiler normalement ; la traduction s’actualise automatiquement.
8. Toucher la bulle verte `AUTO / FR` pour arrêter ce mode.

Pour les utilisations suivantes, ouvrir les réglages rapides du téléphone et
toucher directement **Taobao FR**.

## Sécurité

- les captures restent en mémoire vive et sont supprimées après analyse ;
- aucune capture n’est enregistrée dans la galerie ;
- la sauvegarde Android des données de TaoConnect est désactivée ;
- TaoConnect ne demande jamais les identifiants Taobao ni les coordonnées
  bancaires ;
- il faut arrêter le connecteur avant de saisir un mot de passe, un code de
  vérification ou des informations de paiement ;
- Android affiche une notification tant que la session de capture est active.

## Construire l’APK

Le projet est prêt pour trois méthodes :

- compilation automatisée sous Windows avec `LANCER_COMPILATION_WINDOWS.bat` ;
- compilation automatique grâce à `.github/workflows/build-apk.yml` ;
- compilation locale avec Android Studio, Java 17, Gradle 8.9 et le SDK 35.

Les instructions détaillées se trouvent dans `docs/OBTENIR_APK.md`.

## Architecture

- `MainActivity.kt` : configuration initiale et autorisations ;
- `TaoConnectTileService.kt` : raccourci Android **Taobao FR** ;
- `TranslationOverlayService.kt` : capture, OCR, traduction et bulle ;
- `TranslationTextPolicy.kt` : glossaire Taobao et protection des valeurs sensibles ;
- `TranslationOverlayView.kt` : rendu des étiquettes françaises ;
- `TranslationTextPolicyTest.kt` : tests des règles de traduction ;
- `.github/workflows/build-apk.yml` : contrôles et fabrication de l’APK.

Chaîne de traitement :

`Taobao visible → capture temporaire → OCR chinois → traduction française → superposition`

## Limites actuelles

- Android exige une confirmation de partage d’écran à chaque nouvelle session ;
- seuls les éléments visibles sont traduits ;
- les textes très petits, stylisés ou peu contrastés peuvent être mal reconnus ;
- sur une page très chargée, TaoConnect privilégie les principaux blocs de texte ;
- la conversion CNY → FCFA/euro et la traduction des messages vendeurs restent
  prévues pour une version suivante.

TaoConnect est indépendant et n’est ni affilié, ni approuvé, ni sponsorisé par
Taobao ou Alibaba. Taobao reste responsable de la connexion, du panier et du
paiement.
