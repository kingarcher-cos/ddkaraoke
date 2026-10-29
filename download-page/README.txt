How to put DDKaraoke online (plain steps)

What is in this folder
- index.html   the download page people will see
- logo.png     the logo used on the page
- DDKaraoke-1.0.1.apk   the app itself
- sha256.txt   the file fingerprint (already shown on the page)

To publish
1. Upload ALL of these files together into one folder on your website, for example the folder "karaoke" on doughbaybee.sg.
2. Open the page in a browser to check it loads, and that the Download button downloads the file.
3. Give people the short address of that page (for example doughbaybee.sg/karaoke). On the TV they type it into the Downloader app.

When you release a new version
1. Put the new .apk in the same folder and change the file name in index.html (two places) and the size and fingerprint shown on the page.
2. The fingerprint is made like this (Windows PowerShell): Get-FileHash .\DDKaraoke-NEW.apk -Algorithm SHA256

Keep private
- Never upload the "release-signing" folder from the project. It holds the key used to sign every update.
