"""Count APK downloads across published releases without counting other assets."""
import json
import os
from pathlib import Path
from urllib.request import Request, urlopen

repository = os.environ.get('GITHUB_REPOSITORY', 'J1yann/Cloud')
headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'Cloud-download-counter'}
token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
if token:
    headers['Authorization'] = f'Bearer {token}'
total = 0
page = 1
while True:
    request = Request(f'https://api.github.com/repos/{repository}/releases?per_page=100&page={page}', headers=headers)
    with urlopen(request, timeout=30) as response:
        releases = json.load(response)
    for release in releases:
        if not release['draft']:
            total += sum(asset['download_count'] for asset in release['assets'] if asset['name'].lower().endswith('.apk'))
    if len(releases) < 100:
        break
    page += 1
output = Path('docs/downloads.json')
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps({'schemaVersion': 1, 'label': 'APK downloads', 'message': str(total), 'color': '346E8B'}, indent=2) + '\n')
print(f'Published APK downloads: {total}')
