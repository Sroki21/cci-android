/**
 * Import danych z baza.xlsx do Firebase Firestore.
 *
 * Wymagania:
 *   npm install firebase-admin xlsx
 *
 * Przed uruchomieniem:
 *   1. Pobierz klucz serwisowy: Firebase Console → Ustawienia projektu
 *      → Konta usługi → Wygeneruj nowy klucz prywatny
 *      Zapisz plik jako scripts/firebase-service-account.json
 *   2. Znajdź swój Firebase UID: Firebase Console → Authentication → Users
 *      (wpis z typem "anonymous")
 *   3. Ustaw USER_UID poniżej i uruchom:
 *      node scripts/import-baza.js
 */

const { initializeApp, cert } = require('firebase-admin/app');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const XLSX = require('xlsx');
const path = require('path');

// ─── KONFIGURACJA ────────────────────────────────────────────────────────────
const USER_UID = 'TNY6mvSYgCT0UPgBScS986IdwdH3';
const EXCEL_PATH = path.join('C:', 'Users', 'sroki', 'Downloads', 'baza.xlsx');
const SERVICE_ACCOUNT_PATH = path.join(__dirname, 'firebase-service-account.json');
// ─────────────────────────────────────────────────────────────────────────────

if (USER_UID === 'WKLEJ_SWOJ_UID_TUTAJ') {
    console.error('❌ Ustaw USER_UID w pliku import-baza.js przed uruchomieniem.');
    process.exit(1);
}

initializeApp({ credential: cert(require(SERVICE_ACCOUNT_PATH)) });

const db = getFirestore();
const ts = () => FieldValue.serverTimestamp();
const BATCH_SIZE = 400;

async function batchWrite(ops) {
    for (let i = 0; i < ops.length; i += BATCH_SIZE) {
        const batch = db.batch();
        ops.slice(i, i + BATCH_SIZE).forEach(({ ref, data }) => batch.set(ref, data));
        await batch.commit();
        process.stdout.write(`\r  Zapisano ${Math.min(i + BATCH_SIZE, ops.length)} / ${ops.length}`);
    }
    console.log();
}

async function main() {
    // 1. Wczytaj Excel
    const wb = XLSX.readFile(EXCEL_PATH);
    const ws = wb.Sheets[wb.SheetNames[0]];
    const raw = XLSX.utils.sheet_to_json(ws, {
        header: ['binder', 'page', 'position', 'capId'],
        range: 1,               // pomiń wiersz nagłówka
        raw: true,
    });
    console.log(`📂 Wczytano ${raw.length} wierszy z Excela.`);

    // 2. Sprawdź czy dane już istnieją
    const existing = await db.collection(`users/${USER_UID}/binders`).limit(1).get();
    if (!existing.empty) {
        console.error('⚠️  Klasery już istnieją w Firebase. Usuń je ręcznie przed ponownym importem.');
        process.exit(1);
    }

    // 3. Unikalne segregatory → utwórz klasery
    const uniqueBinders = [...new Set(raw.map(r => r.binder))].sort();
    console.log(`\n📁 Tworzę ${uniqueBinders.length} klaserów...`);
    const binderOps = uniqueBinders.map(name => ({
        ref: db.collection(`users/${USER_UID}/binders`).doc(),
        data: { name, updatedAt: ts() },
    }));
    await batchWrite(binderOps);
    const binderMap = new Map(uniqueBinders.map((name, i) => [name, binderOps[i].ref.id]));

    // 4. Unikalne strony → utwórz strony klaserów
    const uniquePages = [...new Set(raw.map(r => `${r.binder}|||${r.page}`))].sort();
    console.log(`\n📄 Tworzę ${uniquePages.length} stron...`);
    const pageOps = uniquePages.map(key => {
        const [binderName, pageNum] = key.split('|||');
        return {
            ref: db.collection(`users/${USER_UID}/binder_pages`).doc(),
            data: {
                binderFirestoreId: binderMap.get(binderName),
                pageNumber: parseInt(pageNum, 10),
                updatedAt: ts(),
            },
        };
    });
    await batchWrite(pageOps);
    const pageMap = new Map(uniquePages.map((key, i) => [key, pageOps[i].ref.id]));

    // 5. Pozycje kapslów
    console.log(`\n🍺 Tworzę ${raw.length} pozycji kapslów...`);
    const posOps = raw.map(row => ({
        ref: db.collection(`users/${USER_UID}/cap_positions`).doc(),
        data: {
            binderPageFirestoreId: pageMap.get(`${row.binder}|||${row.page}`),
            position: row.position,
            capId: row.capId,
            updatedAt: ts(),
        },
    }));
    await batchWrite(posOps);

    console.log('\n✅ Import zakończony pomyślnie!');
    console.log(`   Klasery: ${uniqueBinders.length}`);
    console.log(`   Strony:  ${uniquePages.length}`);
    console.log(`   Kapsle:  ${raw.length}`);
    process.exit(0);
}

main().catch(err => {
    console.error('\n❌ Błąd:', err.message);
    process.exit(1);
});
