# Obtenir Tao Connect 0.5

Ouvrir l’onglet **Actions** du dépôt GitHub, puis la compilation de la branche `taoconnect-v0.5-bilingual` correspondant au dernier commit. Attendre le résultat vert des tests Android. Télécharger **TaoConnect-v0.5-APK** : il contient le fichier installable `TaoConnect-v0.5-debug.apk`.

Installer ce fichier sur Android 8 ou supérieur, avec Android System WebView à jour. Ouvrir Tao Connect et toucher **Ouvrir Taobao en bilingue**. Aucune autorisation de partage d’écran ni d’affichage sur d’autres applications n’est demandée.

Une compilation debug peut utiliser une signature différente de l’APK précédemment installé. Si Android refuse la mise à jour pour cette raison, désinstaller l’ancienne version avant d’installer celle-ci ; cela supprime les préférences et la session Taobao stockées dans Tao Connect.

Les modèles de traduction doivent être téléchargés une première fois. En cas d’échec réseau, ouvrir le menu puis **Actualiser / réessayer**. Les commandes du glossaire restent disponibles sans attendre ce téléchargement.

## Construire localement

Avec Java 17, Gradle 8.9 et Android SDK 35 :

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# Avec un appareil/émulateur connecté :
gradle :app:connectedDebugAndroidTest
```

L’APK local est dans `app/build/outputs/apk/debug/app-debug.apk`. Le script Windows existant permet aussi de lancer et récupérer une compilation GitHub ; il demande une connexion GitHub officielle, jamais le mot de passe dans un fichier.
