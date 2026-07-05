import urllib.request
import json
import xml.etree.ElementTree as ET
import os
import re

icons = {
    'auto_fix_high': 'https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/auto_fix_high/default/24px.svg',
    'settings': 'https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/settings/default/24px.svg',
    'emoji_emotions': 'https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/emoji_emotions/default/24px.svg'
}

os.makedirs('app/src/main/res/drawable', exist_ok=True)

for name, url in icons.items():
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req) as response:
            svg_data = response.read().decode('utf-8')
            
            # Extract path d
            paths = re.findall(r'<path d="([^"]+)"', svg_data)
            
            xml = f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
'''
            for path in paths:
                xml += f'    <path android:fillColor="#FFFFFFFF" android:pathData="{path}"/>\n'
            xml += '</vector>'
            
            with open(f'app/src/main/res/drawable/ic_{name}.xml', 'w') as f:
                f.write(xml)
            print(f'Created ic_{name}.xml')
    except Exception as e:
        print(f'Failed {name}: {e}')
