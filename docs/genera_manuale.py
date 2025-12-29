#!/usr/bin/env python3
"""
Generatore del Manuale Utente di Tramando
=========================================

Genera manuali utente professionali in italiano e inglese.

Requisiti:
    pip install reportlab pillow

Utilizzo:
    python genera_manuale.py           # Genera entrambe le versioni
    python genera_manuale.py it        # Solo italiano
    python genera_manuale.py en        # Solo inglese
"""

import sys
import os
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import cm
from reportlab.lib.colors import HexColor, white
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_LEFT
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Image, PageBreak,
    Table, TableStyle, KeepTogether
)
from reportlab.lib.utils import ImageReader

# =============================================================================
# COSTANTI
# =============================================================================

COLOR_PRIMARY = HexColor('#c44a4a')   # Rosso Tramando
COLOR_TEXT = HexColor('#3d3225')       # Testo principale
COLOR_MUTED = HexColor('#7a6f5d')      # Testo secondario
COLOR_BEIGE = HexColor('#f5f0e6')      # Sfondo code block
COLOR_LIGHT = HexColor('#faf8f5')      # Sfondo alternato tabelle

MARGIN = 2 * cm
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# =============================================================================
# STILI
# =============================================================================

def create_styles():
    """Crea gli stili per il documento."""
    styles = getSampleStyleSheet()

    styles.add(ParagraphStyle(
        'CoverTitle',
        parent=styles['Title'],
        fontSize=36,
        textColor=COLOR_PRIMARY,
        spaceAfter=20,
        alignment=TA_CENTER
    ))

    styles.add(ParagraphStyle(
        'CoverSubtitle',
        parent=styles['Normal'],
        fontSize=16,
        textColor=COLOR_MUTED,
        spaceAfter=30,
        alignment=TA_CENTER
    ))

    styles.add(ParagraphStyle(
        'ChapterTitle',
        parent=styles['Heading1'],
        fontSize=24,
        textColor=COLOR_PRIMARY,
        spaceBefore=30,
        spaceAfter=20
    ))

    styles.add(ParagraphStyle(
        'SectionTitle',
        parent=styles['Heading2'],
        fontSize=16,
        textColor=COLOR_TEXT,
        spaceBefore=20,
        spaceAfter=10
    ))

    styles.add(ParagraphStyle(
        'SubsectionTitle',
        parent=styles['Heading3'],
        fontSize=13,
        textColor=COLOR_TEXT,
        spaceBefore=15,
        spaceAfter=8
    ))

    styles.add(ParagraphStyle(
        'Body',
        parent=styles['Normal'],
        fontSize=11,
        textColor=COLOR_TEXT,
        spaceAfter=10,
        alignment=TA_JUSTIFY,
        leading=16
    ))

    styles.add(ParagraphStyle(
        'BodyLeft',
        parent=styles['Normal'],
        fontSize=11,
        textColor=COLOR_TEXT,
        spaceAfter=10,
        alignment=TA_LEFT,
        leading=16
    ))

    styles.add(ParagraphStyle(
        'BulletItem',
        parent=styles['Normal'],
        fontSize=11,
        textColor=COLOR_TEXT,
        spaceAfter=6,
        leftIndent=20,
        bulletIndent=10,
        leading=16
    ))

    styles.add(ParagraphStyle(
        'CodeBlock',
        parent=styles['Normal'],
        fontName='Courier',
        fontSize=10,
        textColor=COLOR_TEXT,
        backColor=COLOR_BEIGE,
        spaceBefore=8,
        spaceAfter=8,
        leftIndent=15,
        rightIndent=15,
        leading=14
    ))

    styles.add(ParagraphStyle(
        'Note',
        parent=styles['Normal'],
        fontSize=10,
        textColor=COLOR_MUTED,
        leftIndent=20,
        rightIndent=20,
        spaceBefore=10,
        spaceAfter=10,
        leading=14
    ))

    styles.add(ParagraphStyle(
        'Caption',
        parent=styles['Normal'],
        fontSize=9,
        textColor=COLOR_MUTED,
        alignment=TA_CENTER,
        spaceBefore=5,
        spaceAfter=15
    ))

    styles.add(ParagraphStyle(
        'TOCEntry',
        parent=styles['Normal'],
        fontSize=12,
        textColor=COLOR_TEXT,
        spaceBefore=4,
        spaceAfter=4,
        leftIndent=10
    ))

    styles.add(ParagraphStyle(
        'TableHeader',
        parent=styles['Normal'],
        fontName='Helvetica-Bold',
        fontSize=10,
        textColor=white,
        alignment=TA_LEFT
    ))

    styles.add(ParagraphStyle(
        'TableCell',
        parent=styles['Normal'],
        fontSize=10,
        textColor=COLOR_TEXT,
        alignment=TA_LEFT
    ))

    return styles

# =============================================================================
# UTILITA
# =============================================================================

def get_image_path(lang, filename):
    """Restituisce il percorso completo di un'immagine."""
    return os.path.join(SCRIPT_DIR, 'images', lang, filename)


def add_image(story, lang, filename, caption, styles, width=14*cm):
    """
    Aggiunge un'immagine allo story con aspect ratio corretto.
    """
    path = get_image_path(lang, filename)

    if not os.path.exists(path):
        print(f"  [!] Immagine non trovata: {path}")
        return False

    try:
        img_reader = ImageReader(path)
        orig_w, orig_h = img_reader.getSize()
        aspect = orig_h / float(orig_w)

        height = width * aspect
        MAX_HEIGHT = 18 * cm

        if height > MAX_HEIGHT:
            print(f"  [i] Ridimensionamento {filename}: H={height/cm:.1f}cm > Max")
            height = MAX_HEIGHT
            width = height / aspect

        img = Image(path, width=width, height=height)
        img.hAlign = 'CENTER'
        story.append(img)

        if caption:
            story.append(Paragraph(caption, styles['Caption']))

        return True

    except Exception as e:
        print(f"  [!] Errore immagine {path}: {e}")
        return False


def make_table(data, col_widths=None, style_type='default'):
    """Crea una tabella con stile Tramando."""
    table = Table(data, colWidths=col_widths)

    base_style = [
        ('BACKGROUND', (0, 0), (-1, 0), COLOR_PRIMARY),
        ('TEXTCOLOR', (0, 0), (-1, 0), white),
        ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
        ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ('FONTSIZE', (0, 0), (-1, -1), 10),
        ('BOTTOMPADDING', (0, 0), (-1, 0), 10),
        ('TOPPADDING', (0, 0), (-1, 0), 10),
        ('BOTTOMPADDING', (0, 1), (-1, -1), 8),
        ('TOPPADDING', (0, 1), (-1, -1), 8),
        ('LEFTPADDING', (0, 0), (-1, -1), 8),
        ('RIGHTPADDING', (0, 0), (-1, -1), 8),
        ('GRID', (0, 0), (-1, -1), 0.5, COLOR_MUTED),
        ('VALIGN', (0, 0), (-1, -1), 'MIDDLE'),
    ]

    # Righe alternate
    for i in range(1, len(data)):
        if i % 2 == 0:
            base_style.append(('BACKGROUND', (0, i), (-1, i), COLOR_LIGHT))

    table.setStyle(TableStyle(base_style))
    return table


def add_bullet_list(story, items, styles):
    """Aggiunge una lista puntata."""
    for item in items:
        story.append(Paragraph(f"• {item}", styles['BulletItem']))


def add_numbered_list(story, items, styles):
    """Aggiunge una lista numerata."""
    for i, item in enumerate(items, 1):
        story.append(Paragraph(f"{i}. {item}", styles['BulletItem']))

# =============================================================================
# CONTENUTI ITALIANO
# =============================================================================

IT = {
    'filename': 'Tramando_Manuale_Italiano.pdf',
    'tagline': 'Tessi la tua storia',
    'manual_title': 'Manuale Utente',
    'version': 'Versione 1.1',
    'toc_title': 'Indice',

    'chapters': [
        '1. Introduzione',
        '2. Primi passi',
        '3. Cos\'e il markup',
        '4. L\'interfaccia',
        '5. La Struttura narrativa',
        '6. Gli Aspetti',
        '7. I collegamenti',
        '8. Le annotazioni',
        '9. Cerca e sostituisci',
        '10. La mappa radiale',
        '11. Export PDF',
        '12. Impostazioni',
        '13. Il formato file .trmd',
        '14. Scorciatoie da tastiera',
        'Appendice: Riferimento rapido',
    ],

    'captions': {
        'splash': 'La schermata di benvenuto di Tramando',
        'main': 'L\'interfaccia principale di Tramando',
        'filter': 'Il filtro globale e la ricerca in azione',
        'map': 'La mappa radiale con i collegamenti tra elementi',
        'settings': 'Il pannello delle impostazioni',
    },

    # =========================================================================
    # CAPITOLO 1: INTRODUZIONE
    # =========================================================================
    'ch1': {
        'title': '1. Introduzione',

        's1_title': 'Cos\'e Tramando',
        's1_p1': 'Tramando e uno strumento pensato per scrittori che devono gestire storie complesse. Se stai scrivendo un romanzo con decine di personaggi, una sceneggiatura con molteplici linee narrative, o stai costruendo un mondo immaginario con la sua storia e geografia, Tramando ti aiuta a tenere tutto sotto controllo.',
        's1_p2': 'A differenza di un normale word processor, Tramando non si limita a farti scrivere testo. Ti permette di organizzare la tua storia in blocchi modulari chiamati "chunk", di definire personaggi, luoghi e temi come entita separate, e di collegarli tra loro per vedere come si intrecciano nella narrazione.',
        's1_p3': 'Il risultato e una visione d\'insieme della tua opera che sarebbe impossibile ottenere con strumenti tradizionali: puoi vedere in quali scene appare un personaggio, tracciare lo sviluppo di un tema attraverso i capitoli, o verificare la coerenza della timeline.',

        's2_title': 'L\'origine del nome',
        's2_p1': 'Il nome "Tramando" nasce da un gioco di parole. Da un lato c\'e <b>trama</b>, perche scrivere e essenzialmente tessere fili narrativi, intrecciare storie e destini. Dall\'altro c\'e <b>tramando</b>, il gerundio che evoca il senso di progettare qualcosa, magari anche un crimine. Come diceva qualcuno, la differenza tra lo scrittore e l\'assassino e tenue: semplicemente il primo il progetto non lo mette in atto.',

        's3_title': 'La filosofia: tutto e un chunk',
        's3_p1': 'In Tramando, l\'unita base e il <b>chunk</b>: un blocco di testo con un titolo e un\'identita propria. Un capitolo e un chunk. Una scena e un chunk. Ma anche un personaggio e un chunk, un luogo e un chunk, persino una singola nota puo essere un chunk.',
        's3_p2': 'I chunk possono contenere altri chunk, creando una struttura ad albero completamente flessibile. Non ci sono regole rigide su come organizzare il tuo lavoro: puoi avere Libro > Parte > Capitolo > Scena, oppure semplicemente una lista piatta di scene. Tramando si adatta al tuo modo di pensare e scrivere, non il contrario.',

        's4_title': 'Per chi e Tramando',
        's4_items': [
            '<b>Romanzieri</b> che gestiscono cast numerosi e trame intrecciate, e hanno bisogno di tracciare chi appare dove e quando',
            '<b>Sceneggiatori</b> che devono tenere sotto controllo scene, personaggi, e archi narrativi su piu episodi o atti',
            '<b>Autori di serie</b> che devono mantenere coerenza tra volumi, ricordando dettagli stabiliti nei libri precedenti',
            '<b>Worldbuilder</b> che costruiscono mondi complessi con la loro storia, geografia, e cast di personaggi',
            'Chiunque scriva storie con <b>molti elementi interconnessi</b> e voglia uno strumento per visualizzarli e gestirli',
        ],
    },

    # =========================================================================
    # CAPITOLO 2: PRIMI PASSI
    # =========================================================================
    'ch2': {
        'title': '2. Primi passi',

        's1_title': 'Avviare Tramando',
        's1_p1': 'Tramando e disponibile come applicazione desktop per Mac, Windows e Linux. Una volta installato e avviato, ti accogliera la schermata di benvenuto con tre opzioni chiare per iniziare:',
        's1_items': [
            '<b>Continua il lavoro in corso</b> - Riprende automaticamente l\'ultimo progetto su cui stavi lavorando, esattamente dove l\'avevi lasciato',
            '<b>Nuovo progetto</b> - Crea un progetto completamente vuoto, pronto per accogliere la tua nuova storia',
            '<b>Apri file...</b> - Ti permette di caricare un file .trmd esistente dal tuo computer',
        ],

        's2_title': 'Il primo progetto',
        's2_p1': 'Quando crei un nuovo progetto, Tramando ti presenta un\'interfaccia pulita e intuitiva, divisa in due aree principali. A sinistra trovi la <b>sidebar</b>, che contiene la struttura del tuo progetto: qui vedrai crescere l\'albero dei tuoi capitoli, scene, e tutti gli elementi della storia.',
        's2_p2': 'A destra c\'e l\'<b>editor</b>, lo spazio dove effettivamente scrivi e modifichi i contenuti. L\'editor include funzionalita avanzate come syntax highlighting per il markup, numeri di riga, e la possibilita di passare rapidamente dalla modalita scrittura alla modalita lettura.',

        's3_title': 'Salvare il lavoro',
        's3_p1': 'Tramando salva automaticamente il tuo lavoro ogni pochi secondi. Puoi configurare l\'intervallo di autosalvataggio nelle impostazioni, scegliendo un valore tra 1 e 10 secondi. Questo significa che non perderai mai piu di qualche secondo di lavoro anche in caso di crash o chiusura accidentale.',
        's3_p2': 'Oltre all\'autosalvataggio, puoi salvare manualmente su file cliccando il pulsante <b>Salva</b> nella barra superiore. Il file avra estensione <b>.trmd</b> e sara un file di testo leggibile, che potrai aprire anche con un normale editor di testo se necessario.',
        's3_tip': '<i>Consiglio: anche se l\'autosalvataggio e attivo, e buona pratica salvare regolarmente su file. Cosi avrai sempre un backup esterno che potrai copiare su cloud o chiavetta USB.</i>',
    },

    # =========================================================================
    # CAPITOLO 3: COS'E IL MARKUP
    # =========================================================================
    'ch3': {
        'title': '3. Cos\'e il markup',

        'intro': 'Se hai sempre usato programmi come Microsoft Word o Google Docs, potresti non aver mai sentito parlare di "markup". Niente paura: e un concetto semplice che, una volta capito, ti sembrera naturale e potente.',

        's1_title': 'Formattazione visuale vs markup',
        's1_p1': 'In Word, quando vuoi mettere una parola in grassetto, la selezioni con il mouse e clicchi sul pulsante B nella toolbar. Questo approccio si chiama <b>formattazione visuale</b> o WYSIWYG (What You See Is What You Get): quello che vedi sullo schermo e esattamente quello che ottieni.',
        's1_p2': 'Con il <b>markup</b>, invece, inserisci dei simboli speciali direttamente nel testo. Questi simboli vengono poi interpretati e trasformati nella formattazione desiderata. Per esempio, invece di cliccare un pulsante per il grassetto, scrivi:',
        's1_code': 'Questa parola e **importante**',
        's1_result': 'E il risultato sara: Questa parola e <b>importante</b>',

        's2_title': 'Perche usare il markup?',
        's2_items': [
            '<b>Velocita</b> - Non devi mai togliere le mani dalla tastiera per cercare pulsanti o menu. Scrivi e formatti in un flusso continuo',
            '<b>Portabilita</b> - I file sono puro testo, leggibili su qualsiasi dispositivo e con qualsiasi programma',
            '<b>Controllo</b> - Vedi sempre esattamente cosa c\'e nel documento, senza formattazioni nascoste o stili misteriosi',
            '<b>Leggerezza</b> - File piccoli e veloci, nessun formato proprietario, nessun rischio di corruzione',
        ],

        's3_title': 'Markdown: lo standard',
        's3_p1': 'Tramando usa <b>Markdown</b>, il linguaggio di markup piu diffuso al mondo. Lo trovi su GitHub, Reddit, Discord, Notion, e centinaia di altre piattaforme. Impararlo una volta ti servira ovunque.',
        's3_table_title': 'Comandi Markdown base:',
        's3_table': [
            ['Cosa vuoi', 'Cosa scrivi', 'Risultato'],
            ['Grassetto', '**testo**', 'testo in grassetto'],
            ['Corsivo', '*testo*', 'testo in corsivo'],
            ['Titolo', '# Titolo', 'Intestazione grande'],
            ['Sottotitolo', '## Sottotitolo', 'Intestazione media'],
            ['Elenco puntato', '- elemento', '* elemento'],
            ['Elenco numerato', '1. elemento', '1. elemento'],
        ],

        's4_title': 'Il markup speciale di Tramando',
        's4_p1': 'Oltre al Markdown standard, Tramando aggiunge una sua sintassi per funzionalita specifiche:',
        's4_table': [
            ['Funzione', 'Sintassi', 'Esempio'],
            ['Riferimento aspetto', '[@id]', '[@elena]'],
            ['Annotazione TODO', '[!TODO:testo:priorita:commento]', '[!TODO:riscrivere:1:troppo lungo]'],
            ['Annotazione NOTE', '[!NOTE:testo::commento]', '[!NOTE:verificare data::]'],
            ['Annotazione FIX', '[!FIX:testo:priorita:]', '[!FIX:errore nome:2:]'],
            ['Numero arabo', '[:ORD]', '1, 2, 3...'],
            ['Numero romano', '[:ORD-ROM]', 'I, II, III...'],
        ],

        's5_title': 'Non preoccuparti!',
        's5_p1': 'Tramando evidenzia tutto il markup con colori diversi, rendendo facile distinguere i simboli speciali dal testo normale. Inoltre, il tab <b>Lettura</b> ti mostra sempre il risultato finale, senza alcun simbolo visibile.',
        's5_tip': '<i>Dopo qualche giorno di utilizzo, scrivere **grassetto** o [@personaggio] ti verra naturale quanto cliccare un pulsante. E sarai molto piu veloce.</i>',
    },

    # =========================================================================
    # CAPITOLO 4: L'INTERFACCIA
    # =========================================================================
    'ch4': {
        'title': '4. L\'interfaccia',

        's1_title': 'La barra superiore',
        's1_p1': 'La barra in alto contiene tutti i comandi principali dell\'applicazione:',
        's1_items': [
            '<b>Logo Tramando</b> - Cliccandolo torni alla schermata di benvenuto',
            '<b>Titolo progetto</b> - Mostra il nome del progetto corrente; cliccandolo puoi modificare i metadati (titolo, autore, anno...)',
            '<b>Carica</b> - Apre un file .trmd dal tuo computer',
            '<b>Salva</b> - Scarica il progetto corrente come file .trmd',
            '<b>Esporta</b> - Menu a tendina per esportare in PDF o Markdown',
            '<b>Badge Annotazioni</b> - Mostra il numero totale di annotazioni; cliccandolo apri il pannello annotazioni',
            '<b>Toggle Mappa/Editor</b> - Alterna tra la vista mappa radiale e l\'editor di testo',
            '<b>Ingranaggio</b> - Apre il pannello delle impostazioni',
        ],

        's2_title': 'La sidebar',
        's2_p1': 'Il pannello laterale sinistro e il centro di navigazione del tuo progetto:',
        's2_sub1': 'Campo filtro',
        's2_sub1_p': 'In cima alla sidebar trovi un campo di ricerca che filtra l\'intero progetto. Digitando, vedrai solo gli elementi che contengono il testo cercato, sia nel titolo che nel contenuto.',
        's2_sub2': 'STRUTTURA',
        's2_sub2_p': 'Questa sezione contiene la tua narrativa vera e propria: capitoli, scene, parti. E organizzata come un albero espandibile. Il numero tra parentesi indica quanti elementi contiene.',
        's2_sub3': 'ASPETTI',
        's2_sub3_p': 'Qui trovi i cinque tipi di elementi trasversali, ognuno con il suo colore distintivo: <b>Personaggi</b> (rosso), <b>Luoghi</b> (verde), <b>Temi</b> (arancione), <b>Sequenze</b> (viola), <b>Timeline</b> (blu). Il numero tra parentesi indica in quante scene ogni elemento e utilizzato.',

        's3_title': 'L\'editor',
        's3_p1': 'L\'area principale a destra e dove avviene la scrittura. Include tre tab:',
        's3_items': [
            '<b>Modifica</b> - L\'editor vero e proprio, con numeri di riga e syntax highlighting',
            '<b>Figli / Usato da</b> - Mostra gli elementi contenuti (per la struttura) o le scene che usano questo elemento (per gli aspetti)',
            '<b>Lettura</b> - Anteprima pulita del testo, senza markup visibile',
        ],
        's3_p2': 'Sopra l\'editor trovi: il campo per modificare il titolo, i tag degli aspetti collegati, il pulsante "+ Aspetto" per aggiungere collegamenti, e il selettore "Parent" per spostare l\'elemento nella gerarchia.',
    },

    # =========================================================================
    # CAPITOLO 5: LA STRUTTURA NARRATIVA
    # =========================================================================
    'ch5': {
        'title': '5. La Struttura narrativa',

        's1_title': 'Organizzazione ad albero',
        's1_p1': 'La sezione STRUTTURA nella sidebar contiene il testo della tua storia, organizzato come un albero gerarchico. Ogni elemento puo contenere altri elementi, permettendoti di creare la struttura che preferisci.',
        's1_p2': 'Una struttura tipica potrebbe essere: <b>Libro</b> > <b>Parte</b> > <b>Capitolo</b> > <b>Scena</b>. Ma non ci sono regole fisse: potresti avere solo capitoli, oppure scene senza capitoli, oppure una struttura completamente diversa. Tramando si adatta a te.',

        's2_title': 'Creare nuovi elementi',
        's2_items': [
            'Clicca <b>"+ Nuovo Chunk"</b> nella sidebar per creare un elemento al livello root',
            'Clicca <b>"+ Figlio di [nome]"</b> per creare un elemento annidato dentro quello selezionato',
            'Ogni chunk riceve automaticamente un ID unico (es. cap-1, scene-2)',
            'Puoi modificare l\'ID per renderlo piu significativo (es. "prologo", "climax")',
        ],

        's3_title': 'Numerazione automatica',
        's3_p1': 'Tramando supporta macro speciali nel titolo che vengono sostituite con numeri automatici, basati sulla posizione dell\'elemento tra i suoi fratelli.',
        's3_table': [
            ['Macro', 'Risultato', 'Esempio'],
            ['[:ORD]', 'Numeri arabi', '1, 2, 3, 4...'],
            ['[:ORD-ROM]', 'Romani maiuscoli', 'I, II, III, IV...'],
            ['[:ORD-rom]', 'Romani minuscoli', 'i, ii, iii, iv...'],
            ['[:ORD-ALPHA]', 'Lettere maiuscole', 'A, B, C, D...'],
            ['[:ORD-alpha]', 'Lettere minuscole', 'a, b, c, d...'],
        ],
        's3_example': 'Se scrivi "Capitolo [:ORD]: Il risveglio" come titolo del primo capitolo, apparira come "Capitolo 1: Il risveglio". Il secondo capitolo con "Capitolo [:ORD]: La partenza" diventera "Capitolo 2: La partenza", e cosi via.',
    },

    # =========================================================================
    # CAPITOLO 6: GLI ASPETTI
    # =========================================================================
    'ch6': {
        'title': '6. Gli Aspetti',

        'intro': 'Gli aspetti sono elementi che attraversano la storia in modo trasversale. Non fanno parte della sequenza narrativa lineare, ma si collegano ad essa in vari punti. Tramando definisce cinque tipi di aspetti, ognuno con un colore distintivo.',

        's1_title': 'Personaggi',
        's1_color': 'Colore: rosso (#c44a4a)',
        's1_p1': 'I personaggi sono le entita che abitano il tuo mondo narrativo. Ogni personaggio puo avere una scheda con la sua descrizione, e puoi creare sotto-elementi per organizzare le informazioni: aspetto fisico, background, arco narrativo, relazioni con altri personaggi.',
        's1_p2': 'Collegando un personaggio alle scene in cui appare, potrai sempre sapere dove e quando interviene nella storia, facilitando il controllo della coerenza.',

        's2_title': 'Luoghi',
        's2_color': 'Colore: verde (#4a9a6a)',
        's2_p1': 'I luoghi sono gli spazi dove accadono le cose. Puoi organizzarli gerarchicamente: un Paese contiene Citta, che contengono Quartieri, che contengono Edifici, che contengono Stanze.',
        's2_p2': 'Ogni luogo puo avere la sua descrizione dettagliata, e collegandolo alle scene saprai sempre dove si svolge ogni momento della storia.',

        's3_title': 'Temi',
        's3_color': 'Colore: arancione (#b87333)',
        's3_p1': 'I temi sono le idee e i motivi ricorrenti della tua storia: vendetta, redenzione, amore, tradimento, crescita personale. Definendoli come aspetti e collegandoli alle scene pertinenti, puoi tracciare come ogni tema si sviluppa attraverso la narrazione.',
        's3_p2': 'Questo e particolarmente utile in fase di revisione, quando vuoi assicurarti che un tema sia stato sviluppato adeguatamente o che non sia stato abbandonato a meta storia.',

        's4_title': 'Sequenze',
        's4_color': 'Colore: viola (#8a5ac2)',
        's4_p1': 'Le sequenze sono catene di causa-effetto che attraversano la storia. A differenza della struttura narrativa (che segue l\'ordine di lettura), le sequenze seguono la logica interna degli eventi.',
        's4_p2': 'Per esempio, una sequenza "Vendetta di Marco" potrebbe avere come figli: "Scoperta del tradimento" > "Pianificazione" > "Primo tentativo fallito" > "Successo" > "Conseguenze". Questi passi potrebbero essere sparsi in capitoli diversi, ma la sequenza li tiene collegati.',

        's5_title': 'Timeline',
        's5_color': 'Colore: blu (#4a90c2)',
        's5_p1': 'La timeline contiene eventi in ordine cronologico reale, indipendentemente da come appaiono nella narrazione. E particolarmente utile quando la tua storia non e lineare: flashback, flashforward, o narrazioni parallele.',
        's5_p2': 'Puoi usare date o timestamp nei titoli degli eventi (es. "2024-03-15 08:00 - Risveglio") per mantenere l\'ordine corretto.',
        's5_tip': '<i>Consiglio: usa il formato data ISO (AAAA-MM-GG) nei titoli della timeline per un ordinamento alfabetico che corrisponda all\'ordine cronologico.</i>',

        's6_title': 'Creare aspetti',
        's6_p1': 'Per creare un nuovo aspetto, clicca il pulsante <b>"+ Nuovo aspetto"</b> nella sidebar, sotto la categoria desiderata. Ogni aspetto avra il suo ID univoco e potrai dargli un titolo descrittivo.',
    },

    # =========================================================================
    # CAPITOLO 7: I COLLEGAMENTI
    # =========================================================================
    'ch7': {
        'title': '7. I collegamenti',

        'intro': 'La vera potenza di Tramando sta nei collegamenti tra la struttura narrativa e gli aspetti. Collegando scene a personaggi, luoghi e temi, crei una rete di relazioni che ti permette di navigare e analizzare la tua storia in modi impossibili con strumenti tradizionali.',

        's1_title': 'Sintassi [@id]',
        's1_p1': 'Il modo piu diretto per creare un collegamento e scrivere <b>[@id]</b> nel testo della scena, dove "id" e l\'identificatore dell\'aspetto che vuoi collegare.',
        's1_p2': 'Per esempio, se hai un personaggio con ID "elena", scrivendo [@elena] in una scena crei automaticamente un collegamento. Questo metodo e particolarmente utile quando vuoi segnare il punto esatto in cui un elemento appare nel testo.',

        's2_title': 'Metodo tag',
        's2_p1': 'Un\'alternativa e usare i tag visivi sopra l\'editor:',
        's2_items': [
            'Seleziona la scena che vuoi collegare',
            'Clicca sul pulsante <b>"+ Aspetto"</b> sopra l\'editor',
            'Scegli l\'aspetto dal menu che appare',
            'Il tag apparira sotto il titolo della scena',
        ],
        's2_p2': 'Per rimuovere un collegamento, clicca sulla <b>x</b> accanto al tag.',

        's3_title': 'Tab "Usato da"',
        's3_p1': 'Quando selezioni un aspetto (personaggio, luogo, tema...), il tab "Usato da" ti mostra tutte le scene che lo referenziano. E un modo veloce per rispondere alla domanda: "Dove appare questo elemento nella storia?"',

        's4_title': 'Conteggio nella sidebar',
        's4_p1': 'Nella sidebar, accanto a ogni aspetto, vedi un numero tra parentesi (es. "Elena (6)"). Questo indica in quante scene l\'elemento e collegato, dandoti subito un\'idea della sua importanza nella storia.',

        's5_title': 'Best practices',
        's5_items': [
            'Usa ID brevi e significativi: "elena" e meglio di "personaggio-001"',
            'Crea i collegamenti mentre scrivi, non dopo - e piu facile e mantiene la consistenza',
            'Non esagerare: collega solo gli aspetti veramente rilevanti per ogni scena',
            'Usa i tag per aspetti ricorrenti, [@id] nel testo per riferimenti specifici',
        ],
    },

    # =========================================================================
    # CAPITOLO 8: LE ANNOTAZIONI
    # =========================================================================
    'ch8': {
        'title': '8. Le annotazioni',

        'intro': 'Le annotazioni sono note che lasci per te stesso durante la scrittura. Sono visibili in Tramando ma non appariranno mai nel prodotto finale esportato. Sono il tuo spazio per appunti, promemoria e segnalazioni.',

        's1_title': 'Tipi di annotazione',
        's1_items': [
            '<b>TODO</b> - Cose da fare: "aggiungere descrizione del luogo", "sviluppare il dialogo", "ricercare dettagli storici"',
            '<b>NOTE</b> - Appunti e riflessioni: "verificare questa data", "idea per il sequel", "forse troppo lungo"',
            '<b>FIX</b> - Problemi da correggere: "incongruenza con capitolo 3", "errore nel nome", "timeline non torna"',
        ],

        's2_title': 'Creare annotazioni',
        's2_p1': 'Ci sono due modi per creare un\'annotazione:',
        's2_items': [
            'Seleziona il testo da annotare, clicca destro, e scegli il tipo di annotazione dal menu',
            'Scrivi direttamente la sintassi nel testo',
        ],

        's3_title': 'La sintassi',
        's3_p1': 'Il formato delle annotazioni e:',
        's3_code': '[!TIPO:testo:priorita:commento]',
        's3_examples_title': 'Esempi:',
        's3_examples': [
            '[!TODO:riscrivere questo dialogo:1:troppo formale]',
            '[!NOTE:verificare data storica::controllare enciclopedia]',
            '[!FIX:Marco qui si chiama Luca:3:]',
        ],

        's4_title': 'Pannello Annotazioni',
        's4_p1': 'Nella sidebar, la sezione ANNOTAZIONI raccoglie tutte le annotazioni del progetto, raggruppate per tipo (TODO, FIX, NOTE). Cliccando su un\'annotazione, salti direttamente al punto del testo dove si trova.',
        's4_p2': 'Il badge nella barra superiore mostra il numero totale di annotazioni, dandoti sempre visibilita su quanto lavoro di revisione ti aspetta.',

        's5_note': '<b>Importante:</b> le annotazioni NON appaiono nell\'export PDF. Sono esclusivamente per l\'autore durante il processo di scrittura.',
    },

    # =========================================================================
    # CAPITOLO 9: CERCA E SOSTITUISCI
    # =========================================================================
    'ch9': {
        'title': '9. Cerca e sostituisci',

        'intro': 'Tramando offre strumenti di ricerca potenti per navigare anche i progetti piu grandi. Ci sono due livelli di ricerca: globale (su tutto il progetto) e locale (sul chunk corrente).',

        's1_title': 'Filtro globale',
        's1_p1': 'Il campo di ricerca in cima alla sidebar filtra l\'intero progetto. Mentre digiti, la sidebar mostra solo gli elementi che contengono il testo cercato, sia nel titolo che nel contenuto.',
        's1_features': [
            '<b>[Aa]</b> - Toggle per ricerca case-sensitive (distingue maiuscole/minuscole)',
            '<b>[.*]</b> - Toggle per attivare le espressioni regolari',
            'I risultati appaiono come lista piatta con il percorso completo',
            'Cliccando un risultato, si apre nell\'editor con i match evidenziati',
        ],

        's2_title': 'Ricerca locale',
        's2_p1': 'Premi <b>Ctrl+F</b> (o <b>Cmd+F</b> su Mac) per aprire la barra di ricerca sopra l\'editor. Questa cerca solo nel chunk corrente.',
        's2_features': [
            'Tutti i match sono evidenziati in giallo',
            'Il match corrente e evidenziato in arancione piu intenso',
            'Le frecce <b>&lt;</b> e <b>&gt;</b> navigano tra i match',
            'I tasti <b>freccia su/giu</b> funzionano come alternativa',
            'Il contatore (es. "3/12") mostra la posizione corrente sul totale',
        ],

        's3_title': 'Sostituisci',
        's3_p1': 'Premi <b>Ctrl+H</b> (o <b>Cmd+H</b> su Mac) per aprire la barra di sostituzione. Appare un secondo campo per il testo di sostituzione.',
        's3_features': [
            '<b>Sostituisci</b> - Cambia il match corrente e passa al successivo',
            '<b>Sostituisci tutti</b> - Cambia tutte le occorrenze in una volta',
            'Un messaggio conferma quante sostituzioni sono state effettuate',
            '<b>Ctrl+Z</b> annulla le sostituzioni',
        ],

        's4_title': 'Espressioni regolari',
        's4_p1': 'Attivando il toggle [.*] puoi usare espressioni regolari per ricerche avanzate:',
        's4_examples': [
            '<b>\\bparola\\b</b> - Trova "parola" come parola intera, non come parte di altre parole',
            '<b>cap[ií]tolo</b> - Trova sia "capitolo" che "capítolo"',
            '<b>\\d{4}</b> - Trova sequenze di 4 cifre (utile per cercare anni)',
            '<b>^inizio</b> - Trova "inizio" solo a inizio riga',
        ],
    },

    # =========================================================================
    # CAPITOLO 10: LA MAPPA RADIALE
    # =========================================================================
    'ch10': {
        'title': '10. La mappa radiale',

        'intro': 'La mappa radiale e una visualizzazione grafica della tua storia. Ti permette di "vedere" la trama nel suo insieme, con tutti i collegamenti tra struttura e aspetti rappresentati visivamente.',

        's1_title': 'Leggere la mappa',
        's1_items': [
            '<b>Centro</b> - Il titolo del progetto',
            '<b>Anello interno (blu)</b> - La struttura narrativa: capitoli e scene',
            '<b>Anelli esterni</b> - Gli aspetti, ognuno con il suo colore (rosso personaggi, verde luoghi, etc.)',
            '<b>Linee</b> - I collegamenti tra scene e aspetti',
        ],

        's2_title': 'Interazione',
        's2_items': [
            '<b>Scroll</b> - Zoom in e out',
            '<b>Click</b> - Seleziona un elemento',
            '<b>Hover</b> - Mostra dettagli nel pannello informativo',
            '<b>Drag</b> - Sposta la vista quando sei in zoom',
        ],

        's3_title': 'Pannello informativo',
        's3_p1': 'In basso a sinistra della mappa trovi il pannello informativo, diviso in due sezioni:',
        's3_items': [
            '<b>HOVER</b> - Mostra informazioni sull\'elemento sotto il cursore',
            '<b>SELEZIONE</b> - Mostra informazioni sull\'elemento selezionato con click',
        ],
        's3_p2': 'Per ogni elemento vedi: nome, tipo, ID, e numero di collegamenti.',

        's4_title': 'A cosa serve',
        's4_p1': 'La mappa radiale e utile per:',
        's4_items': [
            'Vedere la distribuzione dei personaggi nella storia',
            'Identificare scene sovraccariche (troppe linee = troppi elementi)',
            'Scoprire elementi isolati (aspetti definiti ma mai usati)',
            'Capire le relazioni tra elementi diversi',
            'Avere una visione d\'insieme per decisioni strutturali',
        ],
    },

    # =========================================================================
    # CAPITOLO 11: EXPORT PDF
    # =========================================================================
    'ch11': {
        'title': '11. Export PDF',

        's1_title': 'Come esportare',
        's1_items': [
            'Clicca su <b>"Esporta"</b> nella barra superiore',
            'Scegli <b>"PDF"</b> dal menu',
            'Il file viene generato e scaricato automaticamente',
        ],

        's2_title': 'Cosa viene incluso',
        's2_items': [
            'Pagina titolo con titolo e autore (presi dai metadati del progetto)',
            'Capitoli con titolo in testa e interruzione di pagina',
            'Scene separate da <b>***</b> centrato',
            'Formattazione Markdown: grassetto, corsivo, intestazioni, liste',
        ],

        's3_title': 'Cosa viene escluso',
        's3_items': [
            'Frontmatter YAML (metadati tecnici)',
            'Riferimenti [@id] agli aspetti',
            'ID e metadati dei chunk',
            'Annotazioni (TODO, NOTE, FIX)',
            'Container degli aspetti e il loro contenuto',
        ],

        's4_note': '<b>In pratica:</b> l\'export contiene solo la narrativa pulita, pronta per la lettura o la stampa. Tutto il "dietro le quinte" rimane nascosto.',

        's5_title': 'Formato tecnico',
        's5_table': [
            ['Proprieta', 'Valore'],
            ['Formato pagina', 'A5'],
            ['Margini', '60pt sopra, 70pt sotto, 50pt lati'],
            ['Font', 'Roboto'],
            ['Titolo capitolo', '18pt bold'],
            ['Corpo testo', '11pt, giustificato'],
            ['Interlinea', '1.4'],
            ['Numeri pagina', 'Centrati in basso'],
        ],

        's6_title': 'Export Markdown',
        's6_p1': 'In alternativa al PDF, puoi esportare in formato Markdown. Questo e utile se vuoi importare il testo in altri programmi (Scrivener, Word, etc.) o se vuoi un backup testuale del tuo lavoro.',
    },

    # =========================================================================
    # CAPITOLO 12: IMPOSTAZIONI
    # =========================================================================
    'ch12': {
        'title': '12. Impostazioni',

        's1_title': 'Temi',
        's1_p1': 'Tramando include quattro temi predefiniti:',
        's1_table': [
            ['Tema', 'Descrizione'],
            ['Tessuto', 'Beige caldo con texture di carta (default)'],
            ['Dark', 'Tema scuro con accenti rosa, per scrittura notturna'],
            ['Light', 'Tema chiaro e minimale'],
            ['Sepia', 'Toni vintage e caldi, simula carta invecchiata'],
        ],

        's2_title': 'Autosalvataggio',
        's2_p1': 'Uno slider ti permette di impostare l\'intervallo di autosalvataggio da 1 a 10 secondi. Il valore predefinito e 3 secondi. L\'autosalvataggio avviene dopo N secondi dall\'ultima modifica.',

        's3_title': 'Colori personalizzati',
        's3_p1': 'Puoi personalizzare tutti i colori dell\'interfaccia in due sezioni:',
        's3_sub1': 'INTERFACCIA',
        's3_sub1_items': ['Sfondo principale', 'Sfondo sidebar', 'Sfondo editor', 'Bordi', 'Testo principale', 'Testo secondario', 'Colore accento'],
        's3_sub2': 'CATEGORIE',
        's3_sub2_items': ['Struttura', 'Personaggi', 'Luoghi', 'Temi', 'Sequenze', 'Timeline'],

        's4_title': 'Lingua',
        's4_p1': 'Tramando e disponibile in Italiano e Inglese. Il cambio lingua modifica solo l\'interfaccia; il contenuto dei tuoi progetti non viene alterato.',

        's5_title': 'Import/Export impostazioni',
        's5_p1': 'Puoi esportare le tue impostazioni in un file .edn e reimportarle su un altro dispositivo. Utile per mantenere lo stesso tema e configurazione su piu computer.',

        's6_title': 'Tutorial',
        's6_p1': 'Il pulsante "Rivedi tutorial" riapre la guida interattiva che hai visto al primo avvio. Utile se vuoi rinfrescare la memoria sulle funzionalita.',
    },

    # =========================================================================
    # CAPITOLO 13: IL FORMATO FILE .TRMD
    # =========================================================================
    'ch13': {
        'title': '13. Il formato file .trmd',

        'intro': 'I file .trmd sono file di testo puro, leggibili con qualsiasi editor. Questo garantisce che i tuoi dati siano sempre accessibili, anche senza Tramando.',

        's1_title': 'Struttura generale',
        's1_items': [
            '<b>Frontmatter YAML</b> - Metadati del progetto, racchiusi tra ---',
            '<b>Contenuto</b> - I chunk con la loro gerarchia',
        ],

        's2_title': 'Frontmatter',
        's2_p1': 'Il frontmatter contiene i metadati del progetto:',
        's2_code': '''---
title: "Il mio romanzo"
author: "Nome Autore"
language: "it"
year: 2024
isbn: ""
publisher: ""
custom:
  genere: "Thriller"
---''',

        's3_title': 'Sintassi chunk',
        's3_code': '''[C:id"Titolo del chunk"][@aspetto1][@aspetto2]
Contenuto del chunk qui...

  [C:figlio"Titolo figlio"]
  Contenuto figlio indentato con 2 spazi''',
        's3_items': [
            '<b>[C:id"titolo"]</b> definisce un chunk con il suo ID e titolo',
            '<b>[@id]</b> crea un collegamento a un aspetto',
            '<b>2 spazi</b> di indentazione = 1 livello di nidificazione',
        ],

        's4_title': 'ID riservati',
        's4_p1': 'Alcuni ID sono riservati per i container degli aspetti:',
        's4_items': ['personaggi', 'luoghi', 'temi', 'sequenze', 'timeline'],
        's4_note': 'Questi ID non possono essere usati per altri elementi.',

        's5_title': 'Annotazioni nel file',
        's5_code': 'Testo con [!TODO:da completare:1:urgente] annotazione.',
    },

    # =========================================================================
    # CAPITOLO 14: SCORCIATOIE DA TASTIERA
    # =========================================================================
    'ch14': {
        'title': '14. Scorciatoie da tastiera',

        's1_table': [
            ['Scorciatoia', 'Azione'],
            ['Ctrl/Cmd + Z', 'Annulla (Undo)'],
            ['Ctrl/Cmd + Shift + Z', 'Ripristina (Redo)'],
            ['Escape', 'Chiude modali e barra ricerca'],
            ['Ctrl/Cmd + F', 'Apre ricerca nel chunk'],
            ['Ctrl/Cmd + H', 'Apre cerca e sostituisci'],
            ['Ctrl/Cmd + Shift + F', 'Focus su filtro globale'],
            ['Freccia su/giu', 'Naviga risultati ricerca'],
            ['F3 / Shift + F3', 'Prossimo/precedente risultato'],
        ],

        's2_note': '<i>Nota: Cmd e per macOS, Ctrl e per Windows/Linux.</i>',

        's3_title': 'Cronologia Undo',
        's3_p1': 'Tramando mantiene le ultime 100 modifiche nella cronologia di undo. Puoi annullare e ripristinare liberamente con le scorciatoie sopra indicate.',
    },

    # =========================================================================
    # APPENDICE
    # =========================================================================
    'appendix': {
        'title': 'Appendice: Riferimento rapido',

        's1_title': 'Sintassi',
        's1_table': [
            ['Elemento', 'Sintassi'],
            ['Chunk', '[C:id"Titolo"]'],
            ['Riferimento aspetto', '[@id]'],
            ['TODO', '[!TODO:testo:priorita:commento]'],
            ['NOTE', '[!NOTE:testo:priorita:commento]'],
            ['FIX', '[!FIX:testo:priorita:commento]'],
            ['Numero arabo', '[:ORD]'],
            ['Romano maiuscolo', '[:ORD-ROM]'],
            ['Romano minuscolo', '[:ORD-rom]'],
            ['Lettera maiuscola', '[:ORD-ALPHA]'],
            ['Lettera minuscola', '[:ORD-alpha]'],
        ],

        's2_title': 'ID riservati',
        's2_table': [
            ['ID', 'Tipo'],
            ['personaggi', 'Container personaggi'],
            ['luoghi', 'Container luoghi'],
            ['temi', 'Container temi'],
            ['sequenze', 'Container sequenze'],
            ['timeline', 'Container timeline'],
        ],

        's3_title': 'Colori mappa',
        's3_table': [
            ['Tipo', 'Colore', 'Hex'],
            ['Struttura', 'Blu', '#4a90c2'],
            ['Personaggi', 'Rosso', '#c44a4a'],
            ['Luoghi', 'Verde', '#4a9a6a'],
            ['Temi', 'Arancione', '#b87333'],
            ['Sequenze', 'Viola', '#8a5ac2'],
            ['Timeline', 'Blu', '#4a90c2'],
        ],

        's4_title': 'Limiti tecnici',
        's4_items': [
            'Cronologia Undo: 100 stati',
            'Numeri romani: 1-3999',
            'Storage locale: ~5-10 MB (dipende dal browser)',
        ],

        'footer': 'Tramando - Tessi la tua storia',
    },
}

# =============================================================================
# CONTENUTI ENGLISH
# =============================================================================

EN = {
    'filename': 'Tramando_Manual_English.pdf',
    'tagline': 'Weave your story',
    'manual_title': 'User Manual',
    'version': 'Version 1.1',
    'toc_title': 'Contents',

    'chapters': [
        '1. Introduction',
        '2. Getting Started',
        '3. What is Markup',
        '4. The Interface',
        '5. Narrative Structure',
        '6. Aspects',
        '7. Connections',
        '8. Annotations',
        '9. Search and Replace',
        '10. Radial Map',
        '11. PDF Export',
        '12. Settings',
        '13. The .trmd File Format',
        '14. Keyboard Shortcuts',
        'Appendix: Quick Reference',
    ],

    'captions': {
        'splash': 'The Tramando welcome screen',
        'main': 'Tramando\'s main interface',
        'filter': 'Global filter and search in action',
        'map': 'The radial map with connections between elements',
        'settings': 'The settings panel',
    },

    # =========================================================================
    # CHAPTER 1: INTRODUCTION
    # =========================================================================
    'ch1': {
        'title': '1. Introduction',

        's1_title': 'What is Tramando',
        's1_p1': 'Tramando is a tool designed for writers who need to manage complex stories. Whether you\'re writing a novel with dozens of characters, a screenplay with multiple narrative threads, or building an imaginary world with its own history and geography, Tramando helps you keep everything under control.',
        's1_p2': 'Unlike a regular word processor, Tramando doesn\'t just let you write text. It lets you organize your story into modular blocks called "chunks", define characters, places and themes as separate entities, and connect them together to see how they interweave in the narrative.',
        's1_p3': 'The result is an overview of your work that would be impossible to achieve with traditional tools: you can see in which scenes a character appears, track the development of a theme through chapters, or verify timeline consistency.',

        's2_title': 'The Origin of the Name',
        's2_p1': 'The name "Tramando" comes from a play on words in Italian. On one hand there\'s <b>trama</b> (plot), because writing is essentially weaving narrative threads, intertwining stories and destinies. On the other hand there\'s <b>tramando</b> (plotting), which evokes the sense of planning something, perhaps even a crime. As someone said, the difference between a writer and a murderer is thin: the former simply doesn\'t execute the plan.',

        's3_title': 'The Philosophy: Everything is a Chunk',
        's3_p1': 'In Tramando, the basic unit is the <b>chunk</b>: a block of text with a title and its own identity. A chapter is a chunk. A scene is a chunk. But also a character is a chunk, a place is a chunk, even a single note can be a chunk.',
        's3_p2': 'Chunks can contain other chunks, creating a completely flexible tree structure. There are no rigid rules on how to organize your work: you can have Book > Part > Chapter > Scene, or simply a flat list of scenes. Tramando adapts to your way of thinking and writing, not the other way around.',

        's4_title': 'Who is Tramando For',
        's4_items': [
            '<b>Novelists</b> managing large casts and intertwined plots, who need to track who appears where and when',
            '<b>Screenwriters</b> who need to keep track of scenes, characters, and narrative arcs across multiple episodes or acts',
            '<b>Series authors</b> who must maintain consistency across volumes, remembering details established in previous books',
            '<b>Worldbuilders</b> constructing complex worlds with their own history, geography, and cast of characters',
            'Anyone writing stories with <b>many interconnected elements</b> who wants a tool to visualize and manage them',
        ],
    },

    # =========================================================================
    # CHAPTER 2: GETTING STARTED
    # =========================================================================
    'ch2': {
        'title': '2. Getting Started',

        's1_title': 'Launching Tramando',
        's1_p1': 'Tramando is available as a desktop application for Mac, Windows, and Linux. Once installed and launched, you\'ll be greeted by the welcome screen with three clear options to get started:',
        's1_items': [
            '<b>Continue current work</b> - Automatically resumes the last project you were working on, exactly where you left off',
            '<b>New project</b> - Creates a completely empty project, ready to welcome your new story',
            '<b>Open file...</b> - Lets you load an existing .trmd file from your computer',
        ],

        's2_title': 'Your First Project',
        's2_p1': 'When you create a new project, Tramando presents a clean and intuitive interface, divided into two main areas. On the left you\'ll find the <b>sidebar</b>, which contains your project structure: here you\'ll see the tree of your chapters, scenes, and all story elements grow.',
        's2_p2': 'On the right is the <b>editor</b>, the space where you actually write and edit content. The editor includes advanced features like syntax highlighting for markup, line numbers, and the ability to quickly switch from writing mode to reading mode.',

        's3_title': 'Saving Your Work',
        's3_p1': 'Tramando automatically saves your work every few seconds. You can configure the autosave interval in settings, choosing a value between 1 and 10 seconds. This means you\'ll never lose more than a few seconds of work even in case of a crash or accidental closure.',
        's3_p2': 'In addition to autosave, you can manually save to a file by clicking the <b>Save</b> button in the top bar. The file will have a <b>.trmd</b> extension and will be a readable text file, which you can also open with a regular text editor if needed.',
        's3_tip': '<i>Tip: even with autosave active, it\'s good practice to save to file regularly. This way you\'ll always have an external backup you can copy to cloud or USB drive.</i>',
    },

    # =========================================================================
    # CHAPTER 3: WHAT IS MARKUP
    # =========================================================================
    'ch3': {
        'title': '3. What is Markup',

        'intro': 'If you\'ve always used programs like Microsoft Word or Google Docs, you may have never heard of "markup". Don\'t worry: it\'s a simple concept that, once understood, will seem natural and powerful.',

        's1_title': 'Visual Formatting vs Markup',
        's1_p1': 'In Word, when you want to make a word bold, you select it with the mouse and click the B button in the toolbar. This approach is called <b>visual formatting</b> or WYSIWYG (What You See Is What You Get): what you see on screen is exactly what you get.',
        's1_p2': 'With <b>markup</b>, instead, you insert special symbols directly into the text. These symbols are then interpreted and transformed into the desired formatting. For example, instead of clicking a button for bold, you write:',
        's1_code': 'This word is **important**',
        's1_result': 'And the result will be: This word is <b>important</b>',

        's2_title': 'Why Use Markup?',
        's2_items': [
            '<b>Speed</b> - You never have to take your hands off the keyboard to search for buttons or menus. You write and format in a continuous flow',
            '<b>Portability</b> - Files are pure text, readable on any device and with any program',
            '<b>Control</b> - You always see exactly what\'s in the document, with no hidden formatting or mysterious styles',
            '<b>Lightness</b> - Small and fast files, no proprietary format, no risk of corruption',
        ],

        's3_title': 'Markdown: The Standard',
        's3_p1': 'Tramando uses <b>Markdown</b>, the most widespread markup language in the world. You\'ll find it on GitHub, Reddit, Discord, Notion, and hundreds of other platforms. Learning it once will serve you everywhere.',
        's3_table_title': 'Basic Markdown commands:',
        's3_table': [
            ['What you want', 'What you write', 'Result'],
            ['Bold', '**text**', 'text in bold'],
            ['Italic', '*text*', 'text in italic'],
            ['Title', '# Title', 'Large heading'],
            ['Subtitle', '## Subtitle', 'Medium heading'],
            ['Bullet list', '- item', '* item'],
            ['Numbered list', '1. item', '1. item'],
        ],

        's4_title': 'Tramando\'s Special Markup',
        's4_p1': 'In addition to standard Markdown, Tramando adds its own syntax for specific features:',
        's4_table': [
            ['Function', 'Syntax', 'Example'],
            ['Aspect reference', '[@id]', '[@elena]'],
            ['TODO annotation', '[!TODO:text:priority:comment]', '[!TODO:rewrite:1:too long]'],
            ['NOTE annotation', '[!NOTE:text::comment]', '[!NOTE:verify date::]'],
            ['FIX annotation', '[!FIX:text:priority:]', '[!FIX:name error:2:]'],
            ['Arabic number', '[:ORD]', '1, 2, 3...'],
            ['Roman number', '[:ORD-ROM]', 'I, II, III...'],
        ],

        's5_title': 'Don\'t Worry!',
        's5_p1': 'Tramando highlights all markup with different colors, making it easy to distinguish special symbols from regular text. Additionally, the <b>Reading</b> tab always shows you the final result, without any visible symbols.',
        's5_tip': '<i>After a few days of use, writing **bold** or [@character] will feel as natural as clicking a button. And you\'ll be much faster.</i>',
    },

    # =========================================================================
    # CHAPTER 4: THE INTERFACE
    # =========================================================================
    'ch4': {
        'title': '4. The Interface',

        's1_title': 'The Top Bar',
        's1_p1': 'The bar at the top contains all the main application commands:',
        's1_items': [
            '<b>Tramando Logo</b> - Clicking it returns you to the welcome screen',
            '<b>Project Title</b> - Shows the current project name; clicking it lets you edit metadata (title, author, year...)',
            '<b>Load</b> - Opens a .trmd file from your computer',
            '<b>Save</b> - Downloads the current project as a .trmd file',
            '<b>Export</b> - Dropdown menu for exporting to PDF or Markdown',
            '<b>Annotations Badge</b> - Shows the total number of annotations; clicking it opens the annotations panel',
            '<b>Map/Editor Toggle</b> - Switches between the radial map view and text editor',
            '<b>Gear Icon</b> - Opens the settings panel',
        ],

        's2_title': 'The Sidebar',
        's2_p1': 'The left side panel is your project\'s navigation center:',
        's2_sub1': 'Filter Field',
        's2_sub1_p': 'At the top of the sidebar you\'ll find a search field that filters the entire project. As you type, you\'ll see only elements that contain the searched text, both in title and content.',
        's2_sub2': 'STRUCTURE',
        's2_sub2_p': 'This section contains your actual narrative: chapters, scenes, parts. It\'s organized as an expandable tree. The number in parentheses indicates how many elements it contains.',
        's2_sub3': 'ASPECTS',
        's2_sub3_p': 'Here you\'ll find the five types of cross-cutting elements, each with its distinctive color: <b>Characters</b> (red), <b>Places</b> (green), <b>Themes</b> (orange), <b>Sequences</b> (purple), <b>Timeline</b> (blue). The number in parentheses indicates how many scenes each element is used in.',

        's3_title': 'The Editor',
        's3_p1': 'The main area on the right is where writing happens. It includes three tabs:',
        's3_items': [
            '<b>Edit</b> - The actual editor, with line numbers and syntax highlighting',
            '<b>Children / Used by</b> - Shows contained elements (for structure) or scenes that use this element (for aspects)',
            '<b>Reading</b> - Clean preview of the text, without visible markup',
        ],
        's3_p2': 'Above the editor you\'ll find: the field to edit the title, tags of connected aspects, the "+ Aspect" button to add connections, and the "Parent" selector to move the element in the hierarchy.',
    },

    # =========================================================================
    # CHAPTER 5: NARRATIVE STRUCTURE
    # =========================================================================
    'ch5': {
        'title': '5. Narrative Structure',

        's1_title': 'Tree Organization',
        's1_p1': 'The STRUCTURE section in the sidebar contains your story\'s text, organized as a hierarchical tree. Each element can contain other elements, allowing you to create whatever structure you prefer.',
        's1_p2': 'A typical structure might be: <b>Book</b> > <b>Part</b> > <b>Chapter</b> > <b>Scene</b>. But there are no fixed rules: you might have only chapters, or scenes without chapters, or a completely different structure. Tramando adapts to you.',

        's2_title': 'Creating New Elements',
        's2_items': [
            'Click <b>"+ New Chunk"</b> in the sidebar to create an element at the root level',
            'Click <b>"+ Child of [name]"</b> to create a nested element inside the selected one',
            'Each chunk automatically receives a unique ID (e.g., cap-1, scene-2)',
            'You can modify the ID to make it more meaningful (e.g., "prologue", "climax")',
        ],

        's3_title': 'Automatic Numbering',
        's3_p1': 'Tramando supports special macros in the title that are replaced with automatic numbers, based on the element\'s position among its siblings.',
        's3_table': [
            ['Macro', 'Result', 'Example'],
            ['[:ORD]', 'Arabic numbers', '1, 2, 3, 4...'],
            ['[:ORD-ROM]', 'Uppercase Roman', 'I, II, III, IV...'],
            ['[:ORD-rom]', 'Lowercase Roman', 'i, ii, iii, iv...'],
            ['[:ORD-ALPHA]', 'Uppercase letters', 'A, B, C, D...'],
            ['[:ORD-alpha]', 'Lowercase letters', 'a, b, c, d...'],
        ],
        's3_example': 'If you write "Chapter [:ORD]: The Awakening" as the title of the first chapter, it will appear as "Chapter 1: The Awakening". The second chapter with "Chapter [:ORD]: The Departure" will become "Chapter 2: The Departure", and so on.',
    },

    # =========================================================================
    # CHAPTER 6: ASPECTS
    # =========================================================================
    'ch6': {
        'title': '6. Aspects',

        'intro': 'Aspects are elements that cross through the story transversally. They\'re not part of the linear narrative sequence, but connect to it at various points. Tramando defines five types of aspects, each with a distinctive color.',

        's1_title': 'Characters',
        's1_color': 'Color: red (#c44a4a)',
        's1_p1': 'Characters are the entities that inhabit your narrative world. Each character can have a profile with their description, and you can create sub-elements to organize information: physical appearance, background, narrative arc, relationships with other characters.',
        's1_p2': 'By connecting a character to the scenes where they appear, you\'ll always know where and when they intervene in the story, making consistency checks easier.',

        's2_title': 'Places',
        's2_color': 'Color: green (#4a9a6a)',
        's2_p1': 'Places are the spaces where things happen. You can organize them hierarchically: a Country contains Cities, which contain Districts, which contain Buildings, which contain Rooms.',
        's2_p2': 'Each place can have its detailed description, and by connecting it to scenes you\'ll always know where each moment of the story takes place.',

        's3_title': 'Themes',
        's3_color': 'Color: orange (#b87333)',
        's3_p1': 'Themes are the recurring ideas and motifs of your story: revenge, redemption, love, betrayal, personal growth. By defining them as aspects and connecting them to relevant scenes, you can track how each theme develops through the narrative.',
        's3_p2': 'This is particularly useful during revision, when you want to ensure a theme has been adequately developed or hasn\'t been abandoned mid-story.',

        's4_title': 'Sequences',
        's4_color': 'Color: purple (#8a5ac2)',
        's4_p1': 'Sequences are cause-and-effect chains that cross through the story. Unlike narrative structure (which follows reading order), sequences follow the internal logic of events.',
        's4_p2': 'For example, a "Marco\'s Revenge" sequence might have as children: "Discovery of betrayal" > "Planning" > "First failed attempt" > "Success" > "Consequences". These steps might be scattered across different chapters, but the sequence keeps them connected.',

        's5_title': 'Timeline',
        's5_color': 'Color: blue (#4a90c2)',
        's5_p1': 'The timeline contains events in actual chronological order, regardless of how they appear in the narrative. It\'s particularly useful when your story isn\'t linear: flashbacks, flash-forwards, or parallel narratives.',
        's5_p2': 'You can use dates or timestamps in event titles (e.g., "2024-03-15 08:00 - Awakening") to maintain the correct order.',
        's5_tip': '<i>Tip: use ISO date format (YYYY-MM-DD) in timeline titles for alphabetical sorting that matches chronological order.</i>',

        's6_title': 'Creating Aspects',
        's6_p1': 'To create a new aspect, click the <b>"+ New aspect"</b> button in the sidebar, under the desired category. Each aspect will have its own unique ID and you can give it a descriptive title.',
    },

    # =========================================================================
    # CHAPTER 7: CONNECTIONS
    # =========================================================================
    'ch7': {
        'title': '7. Connections',

        'intro': 'Tramando\'s real power lies in the connections between narrative structure and aspects. By connecting scenes to characters, places, and themes, you create a network of relationships that lets you navigate and analyze your story in ways impossible with traditional tools.',

        's1_title': '[@id] Syntax',
        's1_p1': 'The most direct way to create a connection is to write <b>[@id]</b> in the scene\'s text, where "id" is the identifier of the aspect you want to connect.',
        's1_p2': 'For example, if you have a character with ID "elena", writing [@elena] in a scene automatically creates a connection. This method is particularly useful when you want to mark the exact point where an element appears in the text.',

        's2_title': 'Tag Method',
        's2_p1': 'An alternative is to use the visual tags above the editor:',
        's2_items': [
            'Select the scene you want to connect',
            'Click the <b>"+ Aspect"</b> button above the editor',
            'Choose the aspect from the menu that appears',
            'The tag will appear below the scene\'s title',
        ],
        's2_p2': 'To remove a connection, click the <b>x</b> next to the tag.',

        's3_title': '"Used by" Tab',
        's3_p1': 'When you select an aspect (character, place, theme...), the "Used by" tab shows you all the scenes that reference it. It\'s a quick way to answer the question: "Where does this element appear in the story?"',

        's4_title': 'Count in Sidebar',
        's4_p1': 'In the sidebar, next to each aspect, you see a number in parentheses (e.g., "Elena (6)"). This indicates how many scenes the element is connected to, giving you an immediate idea of its importance in the story.',

        's5_title': 'Best Practices',
        's5_items': [
            'Use short and meaningful IDs: "elena" is better than "character-001"',
            'Create connections as you write, not after - it\'s easier and maintains consistency',
            'Don\'t overdo it: only connect aspects that are truly relevant to each scene',
            'Use tags for recurring aspects, [@id] in text for specific references',
        ],
    },

    # =========================================================================
    # CHAPTER 8: ANNOTATIONS
    # =========================================================================
    'ch8': {
        'title': '8. Annotations',

        'intro': 'Annotations are notes you leave for yourself during writing. They\'re visible in Tramando but will never appear in the final exported product. They\'re your space for notes, reminders, and flags.',

        's1_title': 'Annotation Types',
        's1_items': [
            '<b>TODO</b> - Things to do: "add location description", "develop the dialogue", "research historical details"',
            '<b>NOTE</b> - Notes and reflections: "verify this date", "idea for sequel", "perhaps too long"',
            '<b>FIX</b> - Problems to fix: "inconsistency with chapter 3", "name error", "timeline doesn\'t work"',
        ],

        's2_title': 'Creating Annotations',
        's2_p1': 'There are two ways to create an annotation:',
        's2_items': [
            'Select the text to annotate, right-click, and choose the annotation type from the menu',
            'Write the syntax directly in the text',
        ],

        's3_title': 'The Syntax',
        's3_p1': 'The annotation format is:',
        's3_code': '[!TYPE:text:priority:comment]',
        's3_examples_title': 'Examples:',
        's3_examples': [
            '[!TODO:rewrite this dialogue:1:too formal]',
            '[!NOTE:verify historical date::check encyclopedia]',
            '[!FIX:Marco is called Luca here:3:]',
        ],

        's4_title': 'Annotations Panel',
        's4_p1': 'In the sidebar, the ANNOTATIONS section collects all project annotations, grouped by type (TODO, FIX, NOTE). Clicking an annotation takes you directly to the point in the text where it\'s located.',
        's4_p2': 'The badge in the top bar shows the total number of annotations, always giving you visibility on how much revision work awaits.',

        's5_note': '<b>Important:</b> annotations do NOT appear in PDF export. They\'re exclusively for the author during the writing process.',
    },

    # =========================================================================
    # CHAPTER 9: SEARCH AND REPLACE
    # =========================================================================
    'ch9': {
        'title': '9. Search and Replace',

        'intro': 'Tramando offers powerful search tools for navigating even the largest projects. There are two levels of search: global (across the entire project) and local (on the current chunk).',

        's1_title': 'Global Filter',
        's1_p1': 'The search field at the top of the sidebar filters the entire project. As you type, the sidebar shows only elements that contain the searched text, both in title and content.',
        's1_features': [
            '<b>[Aa]</b> - Toggle for case-sensitive search',
            '<b>[.*]</b> - Toggle to enable regular expressions',
            'Results appear as a flat list with the full path',
            'Clicking a result opens it in the editor with matches highlighted',
        ],

        's2_title': 'Local Search',
        's2_p1': 'Press <b>Ctrl+F</b> (or <b>Cmd+F</b> on Mac) to open the search bar above the editor. This searches only in the current chunk.',
        's2_features': [
            'All matches are highlighted in yellow',
            'The current match is highlighted in more intense orange',
            'The <b>&lt;</b> and <b>&gt;</b> arrows navigate between matches',
            'The <b>up/down arrow</b> keys work as an alternative',
            'The counter (e.g., "3/12") shows current position out of total',
        ],

        's3_title': 'Replace',
        's3_p1': 'Press <b>Ctrl+H</b> (or <b>Cmd+H</b> on Mac) to open the replace bar. A second field appears for the replacement text.',
        's3_features': [
            '<b>Replace</b> - Changes the current match and moves to the next',
            '<b>Replace all</b> - Changes all occurrences at once',
            'A message confirms how many replacements were made',
            '<b>Ctrl+Z</b> undoes the replacements',
        ],

        's4_title': 'Regular Expressions',
        's4_p1': 'By activating the [.*] toggle you can use regular expressions for advanced searches:',
        's4_examples': [
            '<b>\\bword\\b</b> - Finds "word" as a whole word, not as part of other words',
            '<b>chap[ter]</b> - Finds both "chapter" and "chaptor"',
            '<b>\\d{4}</b> - Finds 4-digit sequences (useful for searching years)',
            '<b>^beginning</b> - Finds "beginning" only at the start of a line',
        ],
    },

    # =========================================================================
    # CHAPTER 10: RADIAL MAP
    # =========================================================================
    'ch10': {
        'title': '10. Radial Map',

        'intro': 'The radial map is a graphical visualization of your story. It lets you "see" the plot as a whole, with all the connections between structure and aspects represented visually.',

        's1_title': 'Reading the Map',
        's1_items': [
            '<b>Center</b> - The project title',
            '<b>Inner ring (blue)</b> - The narrative structure: chapters and scenes',
            '<b>Outer rings</b> - The aspects, each with its color (red characters, green places, etc.)',
            '<b>Lines</b> - The connections between scenes and aspects',
        ],

        's2_title': 'Interaction',
        's2_items': [
            '<b>Scroll</b> - Zoom in and out',
            '<b>Click</b> - Select an element',
            '<b>Hover</b> - Shows details in the info panel',
            '<b>Drag</b> - Move the view when zoomed in',
        ],

        's3_title': 'Info Panel',
        's3_p1': 'At the bottom left of the map you\'ll find the info panel, divided into two sections:',
        's3_items': [
            '<b>HOVER</b> - Shows information about the element under the cursor',
            '<b>SELECTION</b> - Shows information about the element selected by clicking',
        ],
        's3_p2': 'For each element you see: name, type, ID, and number of connections.',

        's4_title': 'What It\'s For',
        's4_p1': 'The radial map is useful for:',
        's4_items': [
            'Seeing character distribution in the story',
            'Identifying overloaded scenes (too many lines = too many elements)',
            'Discovering isolated elements (aspects defined but never used)',
            'Understanding relationships between different elements',
            'Getting an overview for structural decisions',
        ],
    },

    # =========================================================================
    # CHAPTER 11: PDF EXPORT
    # =========================================================================
    'ch11': {
        'title': '11. PDF Export',

        's1_title': 'How to Export',
        's1_items': [
            'Click on <b>"Export"</b> in the top bar',
            'Choose <b>"PDF"</b> from the menu',
            'The file is generated and downloaded automatically',
        ],

        's2_title': 'What\'s Included',
        's2_items': [
            'Title page with title and author (taken from project metadata)',
            'Chapters with title at top and page break',
            'Scenes separated by <b>***</b> centered',
            'Markdown formatting: bold, italic, headings, lists',
        ],

        's3_title': 'What\'s Excluded',
        's3_items': [
            'YAML frontmatter (technical metadata)',
            '[@id] aspect references',
            'Chunk IDs and metadata',
            'Annotations (TODO, NOTE, FIX)',
            'Aspect containers and their content',
        ],

        's4_note': '<b>In practice:</b> the export contains only clean narrative, ready for reading or printing. All the "behind the scenes" remains hidden.',

        's5_title': 'Technical Format',
        's5_table': [
            ['Property', 'Value'],
            ['Page format', 'A5'],
            ['Margins', '60pt top, 70pt bottom, 50pt sides'],
            ['Font', 'Roboto'],
            ['Chapter title', '18pt bold'],
            ['Body text', '11pt, justified'],
            ['Line spacing', '1.4'],
            ['Page numbers', 'Centered at bottom'],
        ],

        's6_title': 'Markdown Export',
        's6_p1': 'As an alternative to PDF, you can export in Markdown format. This is useful if you want to import the text into other programs (Scrivener, Word, etc.) or if you want a text backup of your work.',
    },

    # =========================================================================
    # CHAPTER 12: SETTINGS
    # =========================================================================
    'ch12': {
        'title': '12. Settings',

        's1_title': 'Themes',
        's1_p1': 'Tramando includes four preset themes:',
        's1_table': [
            ['Theme', 'Description'],
            ['Tessuto', 'Warm beige with paper texture (default)'],
            ['Dark', 'Dark theme with pink accents, for night writing'],
            ['Light', 'Light and minimal theme'],
            ['Sepia', 'Vintage and warm tones, simulates aged paper'],
        ],

        's2_title': 'Autosave',
        's2_p1': 'A slider lets you set the autosave interval from 1 to 10 seconds. The default value is 3 seconds. Autosave occurs N seconds after the last modification.',

        's3_title': 'Custom Colors',
        's3_p1': 'You can customize all interface colors in two sections:',
        's3_sub1': 'INTERFACE',
        's3_sub1_items': ['Main background', 'Sidebar background', 'Editor background', 'Borders', 'Main text', 'Secondary text', 'Accent color'],
        's3_sub2': 'CATEGORIES',
        's3_sub2_items': ['Structure', 'Characters', 'Places', 'Themes', 'Sequences', 'Timeline'],

        's4_title': 'Language',
        's4_p1': 'Tramando is available in Italian and English. Changing the language only modifies the interface; your project content is not altered.',

        's5_title': 'Import/Export Settings',
        's5_p1': 'You can export your settings to an .edn file and reimport them on another device. Useful for maintaining the same theme and configuration across multiple computers.',

        's6_title': 'Tutorial',
        's6_p1': 'The "Review tutorial" button reopens the interactive guide you saw on first launch. Useful if you want to refresh your memory on the features.',
    },

    # =========================================================================
    # CHAPTER 13: THE .TRMD FILE FORMAT
    # =========================================================================
    'ch13': {
        'title': '13. The .trmd File Format',

        'intro': '.trmd files are pure text files, readable with any editor. This ensures your data is always accessible, even without Tramando.',

        's1_title': 'General Structure',
        's1_items': [
            '<b>YAML Frontmatter</b> - Project metadata, enclosed between ---',
            '<b>Content</b> - Chunks with their hierarchy',
        ],

        's2_title': 'Frontmatter',
        's2_p1': 'The frontmatter contains project metadata:',
        's2_code': '''---
title: "My Novel"
author: "Author Name"
language: "en"
year: 2024
isbn: ""
publisher: ""
custom:
  genre: "Thriller"
---''',

        's3_title': 'Chunk Syntax',
        's3_code': '''[C:id"Chunk Title"][@aspect1][@aspect2]
Chunk content here...

  [C:child"Child Title"]
  Child content indented with 2 spaces''',
        's3_items': [
            '<b>[C:id"title"]</b> defines a chunk with its ID and title',
            '<b>[@id]</b> creates a connection to an aspect',
            '<b>2 spaces</b> of indentation = 1 level of nesting',
        ],

        's4_title': 'Reserved IDs',
        's4_p1': 'Some IDs are reserved for aspect containers:',
        's4_items': ['personaggi', 'luoghi', 'temi', 'sequenze', 'timeline'],
        's4_note': 'These IDs cannot be used for other elements.',

        's5_title': 'Annotations in File',
        's5_code': 'Text with [!TODO:to complete:1:urgent] annotation.',
    },

    # =========================================================================
    # CHAPTER 14: KEYBOARD SHORTCUTS
    # =========================================================================
    'ch14': {
        'title': '14. Keyboard Shortcuts',

        's1_table': [
            ['Shortcut', 'Action'],
            ['Ctrl/Cmd + Z', 'Undo'],
            ['Ctrl/Cmd + Shift + Z', 'Redo'],
            ['Escape', 'Close modals and search bar'],
            ['Ctrl/Cmd + F', 'Open search in chunk'],
            ['Ctrl/Cmd + H', 'Open search and replace'],
            ['Ctrl/Cmd + Shift + F', 'Focus on global filter'],
            ['Up/Down arrow', 'Navigate search results'],
            ['F3 / Shift + F3', 'Next/previous result'],
        ],

        's2_note': '<i>Note: Cmd is for macOS, Ctrl is for Windows/Linux.</i>',

        's3_title': 'Undo History',
        's3_p1': 'Tramando keeps the last 100 changes in the undo history. You can freely undo and redo with the shortcuts indicated above.',
    },

    # =========================================================================
    # APPENDIX
    # =========================================================================
    'appendix': {
        'title': 'Appendix: Quick Reference',

        's1_title': 'Syntax',
        's1_table': [
            ['Element', 'Syntax'],
            ['Chunk', '[C:id"Title"]'],
            ['Aspect reference', '[@id]'],
            ['TODO', '[!TODO:text:priority:comment]'],
            ['NOTE', '[!NOTE:text:priority:comment]'],
            ['FIX', '[!FIX:text:priority:comment]'],
            ['Arabic number', '[:ORD]'],
            ['Uppercase Roman', '[:ORD-ROM]'],
            ['Lowercase Roman', '[:ORD-rom]'],
            ['Uppercase letter', '[:ORD-ALPHA]'],
            ['Lowercase letter', '[:ORD-alpha]'],
        ],

        's2_title': 'Reserved IDs',
        's2_table': [
            ['ID', 'Type'],
            ['personaggi', 'Characters container'],
            ['luoghi', 'Places container'],
            ['temi', 'Themes container'],
            ['sequenze', 'Sequences container'],
            ['timeline', 'Timeline container'],
        ],

        's3_title': 'Map Colors',
        's3_table': [
            ['Type', 'Color', 'Hex'],
            ['Structure', 'Blue', '#4a90c2'],
            ['Characters', 'Red', '#c44a4a'],
            ['Places', 'Green', '#4a9a6a'],
            ['Themes', 'Orange', '#b87333'],
            ['Sequences', 'Purple', '#8a5ac2'],
            ['Timeline', 'Blue', '#4a90c2'],
        ],

        's4_title': 'Technical Limits',
        's4_items': [
            'Undo history: 100 states',
            'Roman numerals: 1-3999',
            'Local storage: ~5-10 MB (depends on browser)',
        ],

        'footer': 'Tramando - Weave your story',
    },
}

# =============================================================================
# BUILD MANUAL
# =============================================================================

def build_chapter_1(story, T, styles, lang):
    """Capitolo 1: Introduzione"""
    ch = T['ch1']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))

    # Cos'e Tramando
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_p1'], styles['Body']))
    story.append(Paragraph(ch['s1_p2'], styles['Body']))
    story.append(Paragraph(ch['s1_p3'], styles['Body']))

    # Origine del nome
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_p1'], styles['Body']))

    # Filosofia
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))
    story.append(Paragraph(ch['s3_p2'], styles['Body']))

    # Per chi e
    story.append(Paragraph(ch['s4_title'], styles['SectionTitle']))
    add_bullet_list(story, ch['s4_items'], styles)

    story.append(PageBreak())


def build_chapter_2(story, T, styles, lang):
    """Capitolo 2: Primi passi"""
    ch = T['ch2']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))

    # Avviare
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_p1'], styles['Body']))
    add_bullet_list(story, ch['s1_items'], styles)
    story.append(Spacer(1, 0.3*cm))
    add_image(story, lang, 'splash.png', T['captions']['splash'], styles, width=13*cm)

    # Primo progetto
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_p1'], styles['Body']))
    story.append(Paragraph(ch['s2_p2'], styles['Body']))

    # Salvare
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))
    story.append(Paragraph(ch['s3_p2'], styles['Body']))
    story.append(Paragraph(ch['s3_tip'], styles['Note']))

    story.append(PageBreak())


def build_chapter_3(story, T, styles, lang):
    """Capitolo 3: Cos'e il markup"""
    ch = T['ch3']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['intro'], styles['Body']))

    # Formattazione vs markup
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_p1'], styles['Body']))
    story.append(Paragraph(ch['s1_p2'], styles['Body']))
    story.append(Paragraph(ch['s1_code'], styles['CodeBlock']))
    story.append(Paragraph(ch['s1_result'], styles['Body']))

    # Perche markup
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    add_bullet_list(story, ch['s2_items'], styles)

    # Markdown
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))
    story.append(Paragraph(ch['s3_table_title'], styles['Body']))
    story.append(Spacer(1, 0.2*cm))
    story.append(make_table(ch['s3_table'], col_widths=[5*cm, 4*cm, 5*cm]))
    story.append(Spacer(1, 0.3*cm))

    # Markup Tramando
    story.append(Paragraph(ch['s4_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s4_p1'], styles['Body']))
    story.append(Spacer(1, 0.2*cm))
    story.append(make_table(ch['s4_table'], col_widths=[4.5*cm, 6*cm, 5*cm]))
    story.append(Spacer(1, 0.3*cm))

    # Rassicurazione
    story.append(Paragraph(ch['s5_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s5_p1'], styles['Body']))
    story.append(Paragraph(ch['s5_tip'], styles['Note']))

    story.append(PageBreak())


def build_chapter_4(story, T, styles, lang):
    """Capitolo 4: L'interfaccia"""
    ch = T['ch4']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    add_image(story, lang, 'main.png', T['captions']['main'], styles, width=16*cm)

    # Barra superiore
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_p1'], styles['Body']))
    add_bullet_list(story, ch['s1_items'], styles)

    # Sidebar
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_p1'], styles['Body']))
    story.append(Paragraph(ch['s2_sub1'], styles['SubsectionTitle']))
    story.append(Paragraph(ch['s2_sub1_p'], styles['Body']))
    story.append(Paragraph(ch['s2_sub2'], styles['SubsectionTitle']))
    story.append(Paragraph(ch['s2_sub2_p'], styles['Body']))
    story.append(Paragraph(ch['s2_sub3'], styles['SubsectionTitle']))
    story.append(Paragraph(ch['s2_sub3_p'], styles['Body']))

    # Editor
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))
    add_bullet_list(story, ch['s3_items'], styles)
    story.append(Paragraph(ch['s3_p2'], styles['Body']))

    story.append(PageBreak())


def build_chapter_5(story, T, styles, lang):
    """Capitolo 5: La Struttura narrativa"""
    ch = T['ch5']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))

    # Organizzazione
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_p1'], styles['Body']))
    story.append(Paragraph(ch['s1_p2'], styles['Body']))

    # Creare elementi
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    add_bullet_list(story, ch['s2_items'], styles)

    # Numerazione
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))
    story.append(Spacer(1, 0.2*cm))
    story.append(make_table(ch['s3_table'], col_widths=[4*cm, 5*cm, 5*cm]))
    story.append(Spacer(1, 0.3*cm))
    story.append(Paragraph(ch['s3_example'], styles['Note']))

    story.append(PageBreak())


def build_chapter_6(story, T, styles, lang):
    """Capitolo 6: Gli Aspetti"""
    ch = T['ch6']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['intro'], styles['Body']))

    # Personaggi
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(f"<i>{ch['s1_color']}</i>", styles['Note']))
    story.append(Paragraph(ch['s1_p1'], styles['Body']))
    story.append(Paragraph(ch['s1_p2'], styles['Body']))

    # Luoghi
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(f"<i>{ch['s2_color']}</i>", styles['Note']))
    story.append(Paragraph(ch['s2_p1'], styles['Body']))
    story.append(Paragraph(ch['s2_p2'], styles['Body']))

    # Temi
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(f"<i>{ch['s3_color']}</i>", styles['Note']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))
    story.append(Paragraph(ch['s3_p2'], styles['Body']))

    # Sequenze
    story.append(Paragraph(ch['s4_title'], styles['SectionTitle']))
    story.append(Paragraph(f"<i>{ch['s4_color']}</i>", styles['Note']))
    story.append(Paragraph(ch['s4_p1'], styles['Body']))
    story.append(Paragraph(ch['s4_p2'], styles['Body']))

    # Timeline
    story.append(Paragraph(ch['s5_title'], styles['SectionTitle']))
    story.append(Paragraph(f"<i>{ch['s5_color']}</i>", styles['Note']))
    story.append(Paragraph(ch['s5_p1'], styles['Body']))
    story.append(Paragraph(ch['s5_p2'], styles['Body']))
    story.append(Paragraph(ch['s5_tip'], styles['Note']))

    # Creare aspetti
    story.append(Paragraph(ch['s6_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s6_p1'], styles['Body']))

    story.append(PageBreak())


def build_chapter_7(story, T, styles, lang):
    """Capitolo 7: I collegamenti"""
    ch = T['ch7']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['intro'], styles['Body']))

    # Sintassi [@id]
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_p1'], styles['Body']))
    story.append(Paragraph(ch['s1_p2'], styles['Body']))

    # Metodo tag
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_p1'], styles['Body']))
    add_numbered_list(story, ch['s2_items'], styles)
    story.append(Paragraph(ch['s2_p2'], styles['Body']))

    # Tab Usato da
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))

    # Conteggio
    story.append(Paragraph(ch['s4_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s4_p1'], styles['Body']))

    # Best practices
    story.append(Paragraph(ch['s5_title'], styles['SectionTitle']))
    add_bullet_list(story, ch['s5_items'], styles)

    story.append(PageBreak())


def build_chapter_8(story, T, styles, lang):
    """Capitolo 8: Le annotazioni"""
    ch = T['ch8']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['intro'], styles['Body']))

    # Tipi
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    add_bullet_list(story, ch['s1_items'], styles)

    # Creare
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_p1'], styles['Body']))
    add_numbered_list(story, ch['s2_items'], styles)

    # Sintassi
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))
    story.append(Paragraph(ch['s3_code'], styles['CodeBlock']))
    story.append(Paragraph(ch['s3_examples_title'], styles['Body']))
    for ex in ch['s3_examples']:
        story.append(Paragraph(ex, styles['CodeBlock']))

    # Pannello
    story.append(Paragraph(ch['s4_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s4_p1'], styles['Body']))
    story.append(Paragraph(ch['s4_p2'], styles['Body']))

    # Nota importante
    story.append(Spacer(1, 0.3*cm))
    story.append(Paragraph(ch['s5_note'], styles['Note']))

    story.append(PageBreak())


def build_chapter_9(story, T, styles, lang):
    """Capitolo 9: Cerca e sostituisci"""
    ch = T['ch9']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    add_image(story, lang, 'filter.png', T['captions']['filter'], styles, width=14*cm)
    story.append(Paragraph(ch['intro'], styles['Body']))

    # Filtro globale
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_p1'], styles['Body']))
    add_bullet_list(story, ch['s1_features'], styles)

    # Ricerca locale
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_p1'], styles['Body']))
    add_bullet_list(story, ch['s2_features'], styles)

    # Sostituisci
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))
    add_bullet_list(story, ch['s3_features'], styles)

    # Regex
    story.append(Paragraph(ch['s4_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s4_p1'], styles['Body']))
    add_bullet_list(story, ch['s4_examples'], styles)

    story.append(PageBreak())


def build_chapter_10(story, T, styles, lang):
    """Capitolo 10: La mappa radiale"""
    ch = T['ch10']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    add_image(story, lang, 'map.png', T['captions']['map'], styles, width=14*cm)
    story.append(Paragraph(ch['intro'], styles['Body']))

    # Leggere la mappa
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    add_bullet_list(story, ch['s1_items'], styles)

    # Interazione
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    add_bullet_list(story, ch['s2_items'], styles)

    # Pannello info
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))
    add_bullet_list(story, ch['s3_items'], styles)
    story.append(Paragraph(ch['s3_p2'], styles['Body']))

    # A cosa serve
    story.append(Paragraph(ch['s4_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s4_p1'], styles['Body']))
    add_bullet_list(story, ch['s4_items'], styles)

    story.append(PageBreak())


def build_chapter_11(story, T, styles, lang):
    """Capitolo 11: Export PDF"""
    ch = T['ch11']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))

    # Come esportare
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    add_numbered_list(story, ch['s1_items'], styles)

    # Cosa incluso
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    add_bullet_list(story, ch['s2_items'], styles)

    # Cosa escluso
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    add_bullet_list(story, ch['s3_items'], styles)

    story.append(Paragraph(ch['s4_note'], styles['Note']))

    # Formato tecnico
    story.append(Paragraph(ch['s5_title'], styles['SectionTitle']))
    story.append(make_table(ch['s5_table'], col_widths=[6*cm, 8*cm]))
    story.append(Spacer(1, 0.3*cm))

    # Markdown
    story.append(Paragraph(ch['s6_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s6_p1'], styles['Body']))

    story.append(PageBreak())


def build_chapter_12(story, T, styles, lang):
    """Capitolo 12: Impostazioni"""
    ch = T['ch12']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    add_image(story, lang, 'settings.png', T['captions']['settings'], styles, width=10*cm)

    # Temi
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_p1'], styles['Body']))
    story.append(make_table(ch['s1_table'], col_widths=[4*cm, 10*cm]))
    story.append(Spacer(1, 0.3*cm))

    # Autosave
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_p1'], styles['Body']))

    # Colori
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))
    story.append(Paragraph(ch['s3_sub1'], styles['SubsectionTitle']))
    add_bullet_list(story, ch['s3_sub1_items'], styles)
    story.append(Paragraph(ch['s3_sub2'], styles['SubsectionTitle']))
    add_bullet_list(story, ch['s3_sub2_items'], styles)

    # Lingua
    story.append(Paragraph(ch['s4_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s4_p1'], styles['Body']))

    # Import/Export
    story.append(Paragraph(ch['s5_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s5_p1'], styles['Body']))

    # Tutorial
    story.append(Paragraph(ch['s6_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s6_p1'], styles['Body']))

    story.append(PageBreak())


def build_chapter_13(story, T, styles, lang):
    """Capitolo 13: Il formato file .trmd"""
    ch = T['ch13']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['intro'], styles['Body']))

    # Struttura generale
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    add_bullet_list(story, ch['s1_items'], styles)

    # Frontmatter
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_p1'], styles['Body']))
    story.append(Paragraph(ch['s2_code'].replace('\n', '<br/>'), styles['CodeBlock']))

    # Sintassi chunk
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_code'].replace('\n', '<br/>'), styles['CodeBlock']))
    add_bullet_list(story, ch['s3_items'], styles)

    # ID riservati
    story.append(Paragraph(ch['s4_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s4_p1'], styles['Body']))
    add_bullet_list(story, ch['s4_items'], styles)
    story.append(Paragraph(ch['s4_note'], styles['Note']))

    # Annotazioni
    story.append(Paragraph(ch['s5_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s5_code'], styles['CodeBlock']))

    story.append(PageBreak())


def build_chapter_14(story, T, styles, lang):
    """Capitolo 14: Scorciatoie da tastiera"""
    ch = T['ch14']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))

    story.append(make_table(ch['s1_table'], col_widths=[6*cm, 8*cm]))
    story.append(Spacer(1, 0.3*cm))

    story.append(Paragraph(ch['s2_note'], styles['Note']))

    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_p1'], styles['Body']))

    story.append(PageBreak())


def build_appendix(story, T, styles, lang):
    """Appendice: Riferimento rapido"""
    ch = T['appendix']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))

    # Sintassi
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(make_table(ch['s1_table'], col_widths=[5*cm, 9*cm]))
    story.append(Spacer(1, 0.5*cm))

    # ID riservati
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(make_table(ch['s2_table'], col_widths=[5*cm, 9*cm]))
    story.append(Spacer(1, 0.5*cm))

    # Colori mappa
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(make_table(ch['s3_table'], col_widths=[4*cm, 4*cm, 4*cm]))
    story.append(Spacer(1, 0.5*cm))

    # Limiti
    story.append(Paragraph(ch['s4_title'], styles['SectionTitle']))
    add_bullet_list(story, ch['s4_items'], styles)

    # Footer
    story.append(Spacer(1, 2*cm))
    story.append(Paragraph(f"<i>{ch['footer']}</i>", styles['Caption']))


def build_manual(lang):
    """Costruisce il manuale completo."""
    T = IT if lang == 'it' else EN
    styles = create_styles()

    # Cambia directory di lavoro per trovare le immagini
    os.chdir(SCRIPT_DIR)

    doc = SimpleDocTemplate(
        T['filename'],
        pagesize=A4,
        leftMargin=MARGIN,
        rightMargin=MARGIN,
        topMargin=MARGIN,
        bottomMargin=MARGIN
    )

    story = []

    # COPERTINA
    story.append(Spacer(1, 4*cm))
    story.append(Paragraph("Tramando", styles['CoverTitle']))
    story.append(Paragraph(T['tagline'], styles['CoverSubtitle']))
    story.append(Spacer(1, 0.5*cm))
    story.append(Paragraph(T['manual_title'], styles['CoverSubtitle']))
    story.append(Spacer(1, 1*cm))
    add_image(story, lang, 'splash.png', '', styles, width=12*cm)
    story.append(Spacer(1, 1*cm))
    story.append(Paragraph(T['version'], styles['Body']))
    story.append(PageBreak())

    # INDICE
    story.append(Paragraph(T['toc_title'], styles['ChapterTitle']))
    story.append(Spacer(1, 0.5*cm))
    for ch in T['chapters']:
        story.append(Paragraph(ch, styles['TOCEntry']))
    story.append(PageBreak())

    # CAPITOLI
    build_chapter_1(story, T, styles, lang)
    build_chapter_2(story, T, styles, lang)
    build_chapter_3(story, T, styles, lang)
    build_chapter_4(story, T, styles, lang)
    build_chapter_5(story, T, styles, lang)
    build_chapter_6(story, T, styles, lang)
    build_chapter_7(story, T, styles, lang)
    build_chapter_8(story, T, styles, lang)
    build_chapter_9(story, T, styles, lang)
    build_chapter_10(story, T, styles, lang)
    build_chapter_11(story, T, styles, lang)
    build_chapter_12(story, T, styles, lang)
    build_chapter_13(story, T, styles, lang)
    build_chapter_14(story, T, styles, lang)
    build_appendix(story, T, styles, lang)

    # BUILD
    doc.build(story)
    print(f"  Generato: {T['filename']}")


def main():
    args = sys.argv[1:] if len(sys.argv) > 1 else ['it', 'en']

    print("=" * 50)
    print("  Tramando - Generatore Manuale Utente")
    print("=" * 50)

    for lang in args:
        if lang in ['it', 'en']:
            print(f"\nGenerazione manuale {lang.upper()}...")
            build_manual(lang)
        else:
            print(f"[!] Lingua non supportata: {lang}")

    print("\nCompletato!")
    print("=" * 50)


if __name__ == "__main__":
    main()
