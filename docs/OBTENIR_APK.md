# Obtenir l’APK installable

Le projet contient une compilation GitHub Actions dans
`.github/workflows/build-apk.yml`. Elle installe Java, Gradle et le SDK Android,
exécute les contrôles Android puis génère l’APK de test.

## Méthode automatique sous Windows — recommandée

1. Décompresser entièrement `TaoConnect-v0.2-source.zip`.
2. Ouvrir le dossier `TaoConnect-v0.2` obtenu.
3. Double-cliquer sur `LANCER_COMPILATION_WINDOWS.bat`.
4. Accepter l’installation de Git et/ou GitHub CLI uniquement s’ils manquent.
5. Se connecter sur la page officielle GitHub ouverte par le script.
6. Attendre le message vert **APK TAOCONNECT PRÊT**.

Le script crée un dépôt GitHub privé, déclenche la compilation, télécharge le
résultat et ouvre automatiquement le dossier contenant le fichier `.apk`.

## Méthode manuelle avec GitHub

1. Créer un dépôt GitHub privé.
2. Envoyer tout le contenu du dossier du projet à la racine du dépôt.
3. Ouvrir l’onglet **Actions** du dépôt.
4. Choisir **Construire TaoConnect APK**.
5. Cliquer sur **Run workflow**.
6. Après validation, télécharger l’artifact **TaoConnect-v0.2-debug.apk**.
7. Installer `TaoConnect-v0.2-debug.apk` sur le téléphone Android.

Android peut demander d’autoriser l’installation depuis le navigateur ou le
gestionnaire de fichiers utilisé. Cet APK est signé automatiquement avec une
clé de développement : il convient aux essais personnels, pas encore à une
publication sur Google Play.

## Méthode Android Studio

1. Ouvrir le dossier dans Android Studio.
2. Attendre la synchronisation Gradle.
3. Choisir **Build → Build APK(s)**.
4. Récupérer `app/build/outputs/apk/debug/app-debug.apk`.

## Après installation

1. Ouvrir TaoConnect une première fois.
2. Ajouter le raccourci **Taobao FR** aux réglages rapides.
3. Appuyer sur ce raccourci pour connecter TaoConnect et ouvrir Taobao.
4. Accepter la demande Android de partage d’écran pour la session.
5. Dans Taobao, appuyer sur la bulle `文 / FR` pour traduire.

Android exige une nouvelle confirmation de partage d’écran après chaque arrêt
du connecteur ou redémarrage du téléphone. Cette protection ne peut pas être
supprimée par une application normale.
