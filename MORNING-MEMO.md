# Mémo du matin — ce qui a été fait cette nuit (5 nov 2026)

## Résultat

L'app auto-lyrics affiche désormais les paroles **3 lignes plein écran, ligne courante marquée ▶**, sur Android Auto — sans la grosse progress bar / le bouton play qui mangeaient tout l'espace avant. Testé end-to-end en DHU avec Deezer qui joue en boucle.

## Captures de preuve

- `scratchpad/dhu-live-1.png` — "Nightcall" de Kavinsky, ligne courante `▶ It's hard to explain`
- `scratchpad/dhu-live-2.png` — ~7 secondes plus tard, ligne courante `▶ But you're still the same` (défilement live confirmé)
- `scratchpad/dhu-after-cleanup.png` — "Love Triangle" d'Angèle, après suppression du legacy MediaBrowser, une seule entrée Lyrics dans AA

Chemin complet : `C:\Users\THEOPH~1\AppData\Local\Temp\claude\36a4f0dcb0141b4f\4389f567-f9a8-4c4c-b1a7-53bb5ca69090\scratchpad\`

## Pull Request à reviewer

**https://github.com/MrZinzin5/auto-lyrics/pull/1**

Branche : `carappservice` sur `MrZinzin5/auto-lyrics`. 3 commits atomiques :
1. `414219d` — Add CarAppService with 3-line PaneTemplate
2. `64c57cf` — Remove legacy MediaBrowserService plumbing
3. `23a15d5` — Drop unused local variable warning

## Pour re-tester toi-même ce matin

1. Branche le tél en USB (il doit déjà l'être).
2. Sur le tél : Android Auto → Developer settings → tape **"Start head unit server"**.
3. Dans PowerShell :
   ```powershell
   & "C:\Android\platform-tools\adb.exe" forward tcp:5277 tcp:5277
   Start-Process "C:\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
   ```
4. Dans la fenêtre DHU : clique l'icône launcher en bas gauche → clique **"Lyrics"**. Les paroles de Deezer doivent défiler.

## Trade-offs à savoir

- **Refresh toutes les 5,5 s** : c'est la limite du quota Android Auto sur les templates. Si une ligne est très courte (<5,5 s), l'affichage peut avoir jusqu'à 5 s de retard. Pour la plupart des chansons c'est imperceptible.
- **Pas de police custom** : AA impose la typographie. Le highlight de la ligne courante = préfixe `▶` + espaces d'indentation pour les autres lignes. Pas de gras / couleur accent (ces spans sont bannis par le host sur `Row.setTitle`).
- **Pas de contrôles media intégrés** dans l'écran Lyrics. Pour play/pause/skip, tu reviens sur l'app Deezer dans AA (c'est le trade-off assumé qu'on avait discuté).
- **Deezer doit être en lecture** avant d'ouvrir Lyrics. Sinon on affiche "Play a song to see lyrics".

## À décider avec toi

1. **Bouton Sync** : l'ancienne UI avait un sous-menu pour ajuster l'offset (±50 ms) si les paroles sont décalées. Veux-tu qu'on l'ajoute dans le header de la PaneTemplate (2 Actions `+ 50` / `- 50`) ou qu'on laisse tomber ?
2. **Karaoké mot-à-mot** : certaines chansons sur LRCLIB ont les timings par mot. Veux-tu qu'on highlight le mot courant dans la ligne (ex: `Somebody's heart is [gonna] break`) ou qu'on reste ligne par ligne ?
3. **Fermer la PR directement sur `main`** (c'est toi le owner du fork, fusion locale) ou laisser ouverte pour review ultérieure ?
4. **Doit-on déclarer la v2.0.0** dans `build.gradle.kts` et bumper le versionCode ? Actuellement toujours sur 1.9.6.

## Ce qui n'a PAS été fait (volontairement)

- Pas touché à `MediaTracker` : il a encore des champs utiles pour l'app phone companion (`albumArt`, `albumColors`, `translatedLines`). À nettoyer séparément si on veut, pas urgent.
- Pas touché à `MainActivity` (la vue téléphone), ni à `PerformanceActivity` (le mode karaoké hors AA). Ils continuent de marcher comme avant.
- Pas de nouveau keystore. La signing debug du repo est partagée publiquement, mais tant qu'on ne publie pas sur Play c'est OK.

## Environnement laissé en place

- JDK 17 Microsoft OpenJDK : `C:\Users\Theophile\AppData\Local\Programs\Microsoft\jdk-17.0.10.7-hotspot`
- Android SDK 34 + build-tools : `C:\Android\Sdk`
- Gradle 8.4 : `C:\Android\gradle-8.4` (standalone, pas forcément utile puisque wrapper maintenant committé)
- Platform-tools : `C:\Android\platform-tools` (`adb.exe` dispo via PATH user)
- DHU : `C:\Android\Sdk\extras\google\auto\desktop-head-unit.exe`
- Fork : https://github.com/MrZinzin5/auto-lyrics
- Veille Windows désactivée (secteur). Tu peux la réactiver dans Paramètres si tu veux.

Bonne journée.
