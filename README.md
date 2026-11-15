# AA Lynk

Receptor Android Auto pentru ecranul unui Lynk & Co 900 (Flyme Auto), pentru uz personal.
Telefonul se conectează prin Bluetooth, apoi trece pe hotspotul mașinii, iar Android Auto
apare pe ecran.

Proiectul pornește de la [Open Headunit](https://github.com/andreknieriem/open-headunit)
de André Knieriem, care continuă proiectul original Headunit al lui Michael Reid. Codul
rămâne sub aceeași licență, GNU AGPL v3 (vezi `LICENSE` și
`COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt`).

## Ce diferă față de Open Headunit

- Alt identificator de aplicație (`com.arrradu.aa900`), deci se instalează separat.
- Setări implicite pentru Lynk & Co 900: mod wireless Native, pe hotspotul mașinii,
  cu pornire automată odată cu mașina.

## APK

Fiecare modificare urcată în repozitoriu este compilată automat de GitHub Actions.
APK-ul apare în pagina Releases, cu numele `AA-Lynk-<număr>.apk`.

Android Auto este marcă înregistrată Google. Acest proiect nu are legătură cu Google.

## Instalare de pe telefon

`AA-Lynk-Installer-<număr>.apk` este o aplicație mică pentru telefon. Cu telefonul conectat la
hotspotul mașinii, ea descarcă ultima versiune AA Lynk din pagina Releases, o instalează în
mașină prin ADB (portul 5555) și poate citi un raport de diagnostic din mașină.

Clientul ADB din `installer/` provine din
[FeiMi CarHelper](https://github.com/jessehoo89/feimi-carhelper), sub GPL-3.0
(vezi `installer/LICENSE-GPL-3.0`).
