#!/usr/bin/env python3
"""Pravi srpski ćirilični prevod (sr-Cyrl.txt) iz latiničnog (sr.txt).

Upotreba: sr_cyrl.py app/src/main/assets/i18n/sr.txt app/src/main/assets/i18n/sr-Cyrl.txt

Nazivi aplikacija i servisa, skraćenice (GPS, USB, OBD…), markeri ({}, %s, \\n), adrese i
nazivi fajlova ostaju latinicom; nastavci posle crtice (Bluetooth-а) idu ćirilicom.
Posle svake izmene sr.txt pokreni ponovo.
"""
import re,sys
# reči koje ostaju latinicom (nazivi, skraćenice, citati na engleskom)
KEEP=set("""StradaOS Android Auto CarPlay Bluetooth Wi Fi GPS USB OBD OBD-II II ELM327 ADB HTTPS HTTP TLS OSRM TomTom OpenStreetMap OSM mapsforge Spotify YouTube Music Waze Maps OsmAnd Open Headunit AIMP Ko fi Claude Groq Gemini Nova ADW VIN PIN ID API IP MAF MAP EGR PCV EVAP CVVT OCV AVRCP HCN FYT CAN OTG USD Kia Picanto Esri Tesla OK RPM SAE PID DTC JSON json map Download sourceinfo appfilter Prueba CH340 FTDI PL2303 CP2102 URL A7 AA BT LED UV SD IR TTS PDF DNS DPI DevCheck Activity Launcher Nominatim Photon Google Play LCD AM FM RDS MCU ZLINK Telegram WhatsApp Mercedes BMW Audi VW Waze XXXX kmh km mi MB GB KB ms Hz dB P N D R Clinkway Autochips Syu syu com android x y z Apple AutoKit Back CarLink CarbitLink Carlinkit Check engine GitHub github Golf Google Home UI LINK No installed apps work with this accessory OpenAI OpenTopoMap Star StradaDiag TLink Vgate ZLink GPL ABS AI APK QR track backup mph check andreknieriem headunit revived play Benz AMG CC BY""".split())
L='abcčćdđefghijklmnoprsštuvzž'
M={'a':'а','b':'б','v':'в','g':'г','d':'д','đ':'ђ','e':'е','ž':'ж','z':'з','i':'и','j':'ј','k':'к','l':'л','m':'м','n':'н','o':'о','p':'п','r':'р','s':'с','t':'т','ć':'ћ','u':'у','f':'ф','h':'х','c':'ц','č':'ч','š':'ш'}
DI={'lj':'љ','nj':'њ','dž':'џ','Lj':'Љ','Nj':'Њ','Dž':'Џ','LJ':'Љ','NJ':'Њ','DŽ':'Џ'}
def tw(w):
    """Latinica u ćirilicu; lj, nj i dž su jedno slovo (osim „inj-ek", npr. injektor = инјектор)."""
    lw = w.lower()
    out = []
    i = 0
    while i < len(w):
        two = w[i:i + 2]
        if two in DI and not (two.lower() == 'nj' and i > 0 and lw[i - 1] == 'i' and lw.startswith('njek', i)):
            out.append(DI[two])
            i += 2
            continue
        ch = w[i]
        if ch.lower() in M:
            c = M[ch.lower()]
            out.append(c.upper() if ch.isupper() else c)
        else:
            out.append(ch)
        i += 1
    return ''.join(out)
# zaštićeni delovi: markeri, URL-ovi/domeni, fajlovi, kodovi
PROT=re.compile(r'\{[^}]*\}|%[sd]|\\n|https?://\S+|[\w.-]+\.(?:org|com|net|io|map|json|txt|tsv|apk)(?:/[\w./-]*)?|\b[\w.]*\d[\w.]*\b|STRD-[\w-]+|\b\w*[qwxyQWXY]\w*\b')
TOK=re.compile(r"[A-Za-zČĆĐŠŽčćđšž]+")
def conv(s):
    parts=[];pos=0
    for m in PROT.finditer(s):
        parts.append((s[pos:m.start()],True));parts.append((m.group(0),False));pos=m.end()
    parts.append((s[pos:],True))
    res=[]
    for txt,do in parts:
        if not do: res.append(txt);continue
        def rep(m):
            w=m.group(0)
            if w in KEEP: return w
            if w.lower()=='offline': return 'Офлајн' if w[0].isupper() else 'офлајн'
            if w.lower()=='online': return 'Онлајн' if w[0].isupper() else 'онлајн'
            return tw(w)
        res.append(TOK.sub(rep,txt))
    return ''.join(res)
src,dst=sys.argv[1],sys.argv[2]
out=[]
for n,l in enumerate(open(src,encoding='utf-8')):
    l=l.rstrip('\n')
    if n==0: out.append('# StradaOS · srpski prevod (ćirilica), pravi se iz sr.txt skriptom .github/scripts/sr_cyrl.py. Format: španski<TAB>prevod.');continue
    if '\t' in l and not l.startswith('#'):
        k,v=l.split('\t',1); out.append(k+'\t'+conv(v))
    else: out.append(l)
open(dst,'w',encoding='utf-8').write('\n'.join(out)+'\n')
