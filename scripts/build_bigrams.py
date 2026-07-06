import urllib.request
import sqlite3
import os

url = "https://norvig.com/ngrams/count_2w.txt"
db_path = "app/src/main/assets/bigrams.db"

print("Downloading bigram corpus...")
response = urllib.request.urlopen(url)
lines = response.read().decode('utf-8').split('\n')

print(f"Downloaded {len(lines)} lines. Parsing and filtering...")

word_map = {}

for line in lines:
    parts = line.strip().split('\t')
    if len(parts) == 2:
        words = parts[0].split(' ')
        if len(words) == 2:
            w1, w2 = words[0], words[1]
            try:
                freq = int(parts[1])
            except ValueError:
                continue
            
            # Filter low frequency pairs to keep DB tiny
            if freq > 50000:
                if w1 not in word_map:
                    word_map[w1] = []
                word_map[w1].append((w2, freq))

print("Sorting and generating SQLite database...")

os.makedirs("app/src/main/assets", exist_ok=True)
if os.path.exists(db_path):
    os.remove(db_path)

conn = sqlite3.connect(db_path)
cursor = conn.cursor()

cursor.execute('''
    CREATE TABLE IF NOT EXISTS bigrams (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        word1 TEXT NOT NULL,
        word2 TEXT NOT NULL,
        frequency INTEGER NOT NULL,
        lastUsed INTEGER NOT NULL
    )
''')

cursor.execute('''
    CREATE UNIQUE INDEX IF NOT EXISTS index_bigrams_word1_word2 ON bigrams (word1, word2)
''')

cursor.execute('''
    CREATE TABLE IF NOT EXISTS personal_words (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        word TEXT NOT NULL,
        frequency INTEGER NOT NULL,
        lastUsed INTEGER NOT NULL
    )
''')

cursor.execute('''
    CREATE UNIQUE INDEX IF NOT EXISTS index_personal_words_word ON personal_words (word)
''')

cursor.execute("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
# Identity hash isn't strictly necessary if fallbackToDestructiveMigration is OFF and we don't care about version upgrades wiping it, but we MUST add the personal_words table!

insert_data = []
now = 0 
total_inserted = 0

for w1, matches in word_map.items():
    matches.sort(key=lambda x: x[1], reverse=True)
    top_matches = matches[:5]
    for w2, freq in top_matches:
        insert_data.append((w1, w2, min(freq, 2147483647), now))
        total_inserted += 1

cursor.executemany('''
    INSERT INTO bigrams (word1, word2, frequency, lastUsed)
    VALUES (?, ?, ?, ?)
''', insert_data)

conn.commit()
conn.close()

print(f"Successfully generated {db_path} with {total_inserted} high-quality bigrams.")
