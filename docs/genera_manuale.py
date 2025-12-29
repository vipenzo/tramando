#!/usr/bin/env python3
"""
Generatore del Manuale Utente di Tramando
Uso:
    python genera_manuale.py it    # Genera manuale italiano
    python genera_manuale.py en    # Genera manuale inglese
    python genera_manuale.py       # Genera entrambi
"""

import sys
import os
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import cm
from reportlab.lib.colors import HexColor, white
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Image, PageBreak, Table, TableStyle, Paragraph
from reportlab.lib.utils import ImageReader



COLOR_PRIMARY = HexColor('#c44a4a')
COLOR_TEXT = HexColor('#3d3225')
COLOR_MUTED = HexColor('#7a6f5d')
MARGIN = 2 * cm

# Percorso base per le immagini (relativo a dove si trova lo script)
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

def get_image_path(lang, filename):
    return os.path.join(SCRIPT_DIR, 'images', lang, filename)

def create_styles():
    styles = getSampleStyleSheet()
    styles.add(ParagraphStyle('CoverTitle', parent=styles['Title'], fontSize=36, textColor=COLOR_PRIMARY, spaceAfter=20, alignment=TA_CENTER))
    styles.add(ParagraphStyle('CoverSubtitle', parent=styles['Normal'], fontSize=16, textColor=COLOR_MUTED, spaceAfter=30, alignment=TA_CENTER))
    styles.add(ParagraphStyle('ChapterTitle', parent=styles['Heading1'], fontSize=24, textColor=COLOR_PRIMARY, spaceBefore=0, spaceAfter=20))
    styles.add(ParagraphStyle('SectionTitle', parent=styles['Heading2'], fontSize=16, textColor=COLOR_TEXT, spaceBefore=20, spaceAfter=10))
    styles.add(ParagraphStyle('Body', parent=styles['Normal'], fontSize=11, textColor=COLOR_TEXT, spaceAfter=10, alignment=TA_JUSTIFY, leading=16))
    styles.add(ParagraphStyle('CodeBlock', parent=styles['Normal'], fontName='Courier', fontSize=10, textColor=COLOR_TEXT, backColor=HexColor('#f5f0e6'), spaceBefore=5, spaceAfter=5, leftIndent=10, rightIndent=10))
    styles.add(ParagraphStyle('Note', parent=styles['Normal'], fontSize=10, textColor=COLOR_MUTED, leftIndent=20, rightIndent=20, spaceBefore=10, spaceAfter=10))
    styles.add(ParagraphStyle('Caption', parent=styles['Normal'], fontSize=9, textColor=COLOR_MUTED, alignment=TA_CENTER, spaceBefore=5, spaceAfter=15))
    return styles

def make_table(data, col_widths):
    table = Table(data, colWidths=col_widths)
    table.setStyle(TableStyle([
        ('BACKGROUND', (0, 0), (-1, 0), COLOR_PRIMARY),
        ('TEXTCOLOR', (0, 0), (-1, 0), white),
        ('ALIGN', (0, 0), (-1, -1), 'LEFT'),
        ('FONTNAME', (0, 0), (-1, 0), 'Helvetica-Bold'),
        ('FONTSIZE', (0, 0), (-1, -1), 10),
        ('BOTTOMPADDING', (0, 0), (-1, 0), 8),
        ('TOPPADDING', (0, 0), (-1, -1), 6),
        ('GRID', (0, 0), (-1, -1), 0.5, COLOR_MUTED),
    ]))
    return table

def add_image_if_exists(story, lang, filename, caption, styles, width=14*cm, height=None):
    path = get_image_path(lang, filename)
    
    if os.path.exists(path):
        try:
            # 1. Leggiamo le dimensioni reali dell'immagine senza caricarla tutta in memoria
            img_reader = ImageReader(path)
            orig_w, orig_h = img_reader.getSize()
            aspect = orig_h / float(orig_w)

            # 2. Impostiamo un limite massimo di altezza (es. 22cm per stare in una pagina A4 con margini)
            MAX_HEIGHT = 22 * cm 

            # 3. Calcoliamo l'altezza risultante se usassimo la larghezza imposta (14cm)
            final_width = width
            final_height = width * aspect

            # 4. Se l'utente ha forzato un'altezza, usiamo quella (rischio distorsione se non calcolato bene)
            if height:
                final_height = height
                # Opzionale: ricalcola width per mantenere aspect ratio se vuoi
                # final_width = height / aspect 
            
            # 5. CONTROLLO CRITICO: Se l'altezza calcolata sfora il limite, ridimensioniamo
            elif final_height > MAX_HEIGHT:
                print(f"  [i] Ridimensionamento auto per {filename}: H={final_height/cm:.1f}cm > Max")
                final_height = MAX_HEIGHT
                final_width = final_height / aspect  # Riduciamo la larghezza per mantenere le proporzioni

            # Creazione del Flowable con dimensioni sicure
            img = Image(path, width=final_width, height=final_height)
            
            # Opzionale: Centrare l'immagine
            img.hAlign = 'CENTER' 

            story.append(img)
            if caption:
                story.append(Paragraph(caption, styles['Caption']))
            return True
            
        except Exception as e:
            print(f"  [!] Errore elaborazione immagine {path}: {e}")
            return False

    print(f"  [!] Immagine non trovata: {path}")
    return False
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
        '3. Cos\'è il markup',
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
        'filter': 'Il filtro globale in azione',
        'map': 'La mappa radiale con collegamenti',
        'settings': 'Il pannello impostazioni',
        'project_info': 'Informazioni progetto',
    },
    'ch1': {
        'title': '1. Introduzione',
        's1_title': 'Cos\'è Tramando',
        's1_text': 'Tramando è uno strumento per scrittori che vogliono gestire storie complesse: personaggi, luoghi, temi, linee temporali. È pensato per chi scrive romanzi, sceneggiature, o qualsiasi narrativa con molti elementi da tenere sotto controllo.',
        's1_text2': 'Il nome viene da <b>trama</b> — perché scrivere è tessere fili narrativi — e da <b>tramando</b>, che dà un po\' il senso di progettare un crimine, perché, diciamocelo, la differenza tra lo scrittore e l\'assassino è tenue: semplicemente il primo il progetto non lo mette in atto.',
        's2_title': 'La filosofia',
        's2_text': 'In Tramando, tutto è un <b>chunk</b>: un blocco di testo con un titolo e un\'identità. Un capitolo è un chunk. Una scena è un chunk. Un personaggio è un chunk. Anche una singola nota può essere un chunk.',
        's2_text2': 'I chunk possono contenere altri chunk, creando una struttura ad albero flessibile. Non ci sono regole rigide su come organizzare il tuo lavoro: Tramando si adatta al tuo modo di scrivere.',
        's3_title': 'Per chi è Tramando',
        's3_text': '• <b>Romanzieri</b> che gestiscono cast numerosi e trame intrecciate<br/>• <b>Sceneggiatori</b> che devono tracciare scene e personaggi<br/>• <b>Autori di serie</b> che devono mantenere coerenza tra volumi<br/>• <b>Worldbuilder</b> che costruiscono mondi complessi<br/>• Chiunque scriva storie con <b>molti elementi interconnessi</b>',
    },
    'ch2': {
        'title': '2. Primi passi',
        's1_title': 'Avviare Tramando',
        's1_text': 'Tramando è disponibile come applicazione desktop per Mac, Windows e Linux. Al primo avvio vedrai la <b>schermata di benvenuto</b> con tre opzioni:',
        's1_options': '• <b>Continua il lavoro in corso</b> — Riprende l\'ultimo progetto salvato<br/>• <b>Nuovo progetto</b> — Crea un progetto vuoto<br/>• <b>Apri file...</b> — Carica un file .trmd esistente',
        's2_title': 'Il primo progetto',
        's2_text': 'Quando crei un nuovo progetto, Tramando ti presenta un\'interfaccia divisa in due aree principali:',
        's2_areas': '• La <b>sidebar</b> a sinistra, con la struttura del tuo progetto<br/>• L\'<b>editor</b> a destra, dove scrivi e modifichi i contenuti',
        's3_title': 'Salvare il lavoro',
        's3_text': 'Tramando salva automaticamente il tuo lavoro ogni pochi secondi. Puoi regolare l\'intervallo nelle impostazioni (da 1 a 10 secondi).',
        's3_text2': 'Per salvare su file, clicca <b>Salva</b> nella barra superiore. Il file avrà estensione <b>.trmd</b>.',
        's3_tip': '<i>Consiglio: salva regolarmente su file oltre all\'autosalvataggio, così avrai sempre un backup esterno.</i>',
    },
    'ch3': {
        'title': '3. Cos\'è il markup',
        'intro': 'Se hai sempre usato programmi come Word, potresti non aver mai sentito parlare di <b>markup</b>. Niente paura: è un concetto semplice.',
        's1_title': 'Formattazione visuale vs markup',
        's1_text': 'In Word, quando vuoi il grassetto, selezioni il testo e clicchi un pulsante. Questo si chiama <b>formattazione visuale</b>.',
        's1_text2': 'Con il <b>markup</b>, invece, inserisci dei simboli speciali nel testo. Esempio: invece di cliccare \'Grassetto\', scrivi:',
        's1_code': 'Questa parola è **importante**',
        's1_result': 'E il risultato sarà: Questa parola è <b>importante</b>',
        's2_title': 'Perché usare il markup?',
        's2_text': '• <b>Velocità</b> — Non devi togliere le mani dalla tastiera<br/>• <b>Portabilità</b> — I file sono puro testo, leggibili ovunque<br/>• <b>Controllo</b> — Vedi esattamente cosa c\'è nel documento<br/>• <b>Leggerezza</b> — File piccoli, nessun formato proprietario',
        's3_title': 'Markdown: il markup più diffuso',
        's3_text': 'Tramando usa <b>Markdown</b>, usato anche da GitHub, Reddit, Discord e Notion.',
        's4_title': 'Non preoccuparti!',
        's4_text': 'Tramando evidenzia il markup con colori diversi, così è facile distinguerlo. E il tab <b>Lettura</b> mostra sempre il risultato finale.',
        's4_tip': '<i>Dopo qualche giorno, scrivere **grassetto** ti verrà naturale quanto cliccare un pulsante.</i>',
    },
    'ch4': {
        'title': '4. L\'interfaccia',
        's1_title': 'La barra superiore',
        's1_text': 'In alto trovi: Logo, Titolo progetto, Carica, Salva, Esporta, contatore Annotazioni, toggle Mappa/Editor, Impostazioni.',
        's2_title': 'La sidebar',
        's2_text': 'A sinistra: <b>Campo filtro</b> in cima per cercare in tutto il progetto, <b>STRUTTURA</b> con la narrativa, <b>ASPETTI</b> con personaggi, luoghi, temi, sequenze, timeline.',
        's3_title': 'L\'editor',
        's3_text': 'A destra: i tab <b>Modifica</b>, <b>Figli/Usato da</b>, <b>Lettura</b>. Sopra l\'editor: titolo, tag aspetti, pulsante + Aspetto, selettore Parent.',
    },
    'ch5': {
        'title': '5. La Struttura narrativa',
        's1_title': 'Creare la gerarchia',
        's1_text': 'La sezione STRUTTURA contiene il testo della tua storia, organizzato come un albero. Esempio: Libro → Parte → Capitolo → Scena.',
        's2_title': 'Creare nuovi elementi',
        's2_text': 'Clicca <b>+ Nuovo Chunk</b> nella sidebar, oppure <b>+ Figlio di...</b> per creare elementi annidati.',
        's3_title': 'Numerazione automatica',
        's3_text': 'Usa macro nel titolo: <b>[:ORD]</b> per 1,2,3..., <b>[:ORD-ROM]</b> per I,II,III..., <b>[:ORD-ALPHA]</b> per A,B,C...',
    },
    'ch6': {
        'title': '6. Gli Aspetti',
        'intro': 'Gli aspetti attraversano la storia: <b>Personaggi</b> (rosso), <b>Luoghi</b> (verde), <b>Temi</b> (arancione), <b>Sequenze</b> (viola), <b>Timeline</b> (blu).',
        'text': 'Ogni aspetto può avere sotto-elementi. Per crearne uno, clicca <b>+ Nuovo aspetto</b> nella sidebar.',
    },
    'ch7': {
        'title': '7. I collegamenti',
        's1_title': 'La sintassi [@id]',
        's1_text': 'Scrivi <b>[@id]</b> nel testo per collegare una scena a un aspetto. Esempio: [@elena] per indicare che Elena appare in quella scena.',
        's2_title': 'Metodo alternativo: i tag',
        's2_text': 'Clicca <b>+ Aspetto</b> sopra l\'editor e scegli dal menu. I tag appaiono sotto il titolo.',
        's3_title': 'Il tab "Usato da"',
        's3_text': 'Selezionando un aspetto, vedi tutte le scene che lo referenziano.',
    },
    'ch8': {
        'title': '8. Le annotazioni',
        'intro': 'Lascia note per te stesso: <b>TODO</b> (cose da fare), <b>NOTE</b> (appunti), <b>FIX</b> (problemi).',
        's1_title': 'Creare annotazioni',
        's1_text': 'Seleziona testo, click destro, scegli il tipo. Sintassi: <b>[!TODO:testo:priorità:commento]</b>',
        's2_title': 'Il pannello Annotazioni',
        's2_text': 'Nella sidebar, sezione ANNOTAZIONI, vedi tutte le annotazioni raggruppate. Cliccando, salti al punto nel testo.',
        'note': '<i>Le annotazioni NON appaiono nell\'export PDF.</i>',
    },
    'ch9': {
        'title': '9. Cerca e sostituisci',
        's1_title': 'Filtro globale',
        's1_text': 'Il campo in cima alla sidebar filtra l\'intero progetto. Supporta <b>[Aa]</b> per maiuscole/minuscole e <b>[.*]</b> per regex.',
        's2_title': 'Ricerca locale',
        's2_text': 'Premi <b>Ctrl+F</b> per cercare nel chunk corrente. Usa le frecce ‹ › per navigare tra i match.',
        's3_title': 'Sostituisci',
        's3_text': 'Premi <b>Ctrl+H</b> per aprire il replace. <b>Sostituisci</b> cambia il match corrente, <b>Sostituisci tutti</b> cambia tutte le occorrenze.',
    },
    'ch10': {
        'title': '10. La mappa radiale',
        'intro': 'Visualizzazione grafica: struttura al centro, aspetti intorno, linee colorate per i collegamenti.',
        's1_title': 'Interazione',
        's1_text': 'Scroll per zoom, click per selezionare, hover per dettagli nel pannello in basso a sinistra.',
    },
    'ch11': {
        'title': '11. Export PDF',
        's1_title': 'Come esportare',
        's1_text': 'Clicca <b>Esporta → PDF</b>. Include pagina titolo, capitoli con interruzione pagina, scene separate da ***.',
        's2_title': 'Cosa viene escluso',
        's2_text': 'Frontmatter YAML, riferimenti [@id], annotazioni, metadati, container aspetti.',
    },
    'ch12': {
        'title': '12. Impostazioni',
        's1_title': 'Temi',
        's1_text': 'Quattro temi: <b>Tessuto</b> (default), <b>Dark</b>, <b>Light</b>, <b>Sepia</b>.',
        's2_title': 'Altre opzioni',
        's2_text': 'Ritardo autosave (1-10 sec), colori personalizzati, lingua (italiano/inglese), import/export settings.',
    },
    'ch13': {
        'title': '13. Il formato file .trmd',
        'intro': 'File testuale con: <b>Frontmatter YAML</b> (metadati tra ---) e <b>Contenuto</b> (chunk con indentazione).',
        's1_title': 'Sintassi chunk',
        's1_code': '[C:id"Titolo"][@aspetto1][@aspetto2]\nContenuto...\n\n  [C:figlio"Titolo figlio"]\n  Contenuto figlio (2 spazi di indent)',
        's2_title': 'ID riservati',
        's2_text': 'personaggi, luoghi, temi, sequenze, timeline',
    },
    'ch14': {
        'title': '14. Scorciatoie da tastiera',
        'intro': 'Ctrl/Cmd+Z (Undo), Ctrl/Cmd+Shift+Z (Redo), Escape (chiudi), Ctrl/Cmd+F (cerca), Ctrl/Cmd+H (replace), Ctrl/Cmd+Shift+F (filtro globale), ↑/↓ (naviga risultati).',
        'note': '<i>Cmd è per macOS, Ctrl per Windows/Linux.</i>',
    },
    'appendix': {
        'title': 'Appendice: Riferimento rapido',
        'footer': '<i>Tramando — Tessi la tua storia</i>',
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
        '2. Getting started', 
        '3. What is markup',
        '4. The interface',
        '5. Narrative Structure',
        '6. Aspects',
        '7. Connections',
        '8. Annotations',
        '9. Search and replace',
        '10. Radial map',
        '11. PDF Export',
        '12. Settings',
        '13. The .trmd file format',
        '14. Keyboard shortcuts',
        'Appendix: Quick reference',
    ],
    'captions': {
        'splash': 'The Tramando welcome screen',
        'main': 'Tramando\'s main interface',
        'filter': 'The global filter in action',
        'map': 'The radial map with connections',
        'settings': 'The settings panel',
        'project_info': 'Project information',
    },
    'ch1': {
        'title': '1. Introduction',
        's1_title': 'What is Tramando',
        's1_text': 'Tramando is a tool for writers who want to manage complex stories: characters, places, themes, timelines. It\'s designed for novelists, screenwriters, or anyone writing narratives with many elements to track.',
        's1_text2': 'The name comes from the Italian <b>trama</b> (plot) — because writing is weaving narrative threads — and <b>tramando</b> (plotting), which gives a sense of planning a crime, because, let\'s face it, the difference between a writer and a murderer is thin: the former simply doesn\'t execute the plan.',
        's2_title': 'The philosophy',
        's2_text': 'In Tramando, everything is a <b>chunk</b>: a block of text with a title and an identity. A chapter is a chunk. A scene is a chunk. A character is a chunk. Even a single note can be a chunk.',
        's2_text2': 'Chunks can contain other chunks, creating a flexible tree structure. There are no rigid rules on how to organize your work: Tramando adapts to your way of writing.',
        's3_title': 'Who is Tramando for',
        's3_text': '• <b>Novelists</b> managing large casts and intertwined plots<br/>• <b>Screenwriters</b> who need to track scenes and characters<br/>• <b>Series authors</b> who must maintain consistency across volumes<br/>• <b>Worldbuilders</b> constructing complex worlds<br/>• Anyone writing stories with <b>many interconnected elements</b>',
    },
    'ch2': {
        'title': '2. Getting started',
        's1_title': 'Launching Tramando',
        's1_text': 'Tramando is available as a desktop application for Mac, Windows and Linux. On first launch you\'ll see the <b>welcome screen</b> with three options:',
        's1_options': '• <b>Continue current work</b> — Resumes the last saved project<br/>• <b>New project</b> — Creates an empty project<br/>• <b>Open file...</b> — Loads an existing .trmd file',
        's2_title': 'Your first project',
        's2_text': 'When you create a new project, Tramando presents an interface divided into two main areas:',
        's2_areas': '• The <b>sidebar</b> on the left, with your project structure<br/>• The <b>editor</b> on the right, where you write and edit content',
        's3_title': 'Saving your work',
        's3_text': 'Tramando automatically saves your work every few seconds. You can adjust the interval in settings (from 1 to 10 seconds).',
        's3_text2': 'To save to a file, click <b>Save</b> in the top bar. The file will have a <b>.trmd</b> extension.',
        's3_tip': '<i>Tip: save to file regularly in addition to autosave, so you always have an external backup.</i>',
    },
    'ch3': {
        'title': '3. What is markup',
        'intro': 'If you\'ve always used programs like Word, you may have never heard of <b>markup</b>. Don\'t worry: it\'s a simple concept.',
        's1_title': 'Visual formatting vs markup',
        's1_text': 'In Word, when you want bold text, you select it and click a button. This is called <b>visual formatting</b>.',
        's1_text2': 'With <b>markup</b>, instead, you insert special symbols in the text. Example: instead of clicking \'Bold\', you write:',
        's1_code': 'This word is **important**',
        's1_result': 'And the result will be: This word is <b>important</b>',
        's2_title': 'Why use markup?',
        's2_text': '• <b>Speed</b> — You don\'t need to take your hands off the keyboard<br/>• <b>Portability</b> — Files are pure text, readable anywhere<br/>• <b>Control</b> — You see exactly what\'s in the document<br/>• <b>Lightness</b> — Small files, no proprietary format',
        's3_title': 'Markdown: the most popular markup',
        's3_text': 'Tramando uses <b>Markdown</b>, also used by GitHub, Reddit, Discord and Notion.',
        's4_title': 'Don\'t worry!',
        's4_text': 'Tramando highlights markup with different colors, so it\'s easy to distinguish. And the <b>Reading</b> tab always shows the final result.',
        's4_tip': '<i>After a few days, writing **bold** will feel as natural as clicking a button.</i>',
    },
    'ch4': {
        'title': '4. The interface',
        's1_title': 'The top bar',
        's1_text': 'At the top: Logo, Project title, Load, Save, Export, Annotations counter, Map/Editor toggle, Settings.',
        's2_title': 'The sidebar',
        's2_text': 'On the left: <b>Filter field</b> at top to search the entire project, <b>STRUCTURE</b> with the narrative, <b>ASPECTS</b> with characters, places, themes, sequences, timeline.',
        's3_title': 'The editor',
        's3_text': 'On the right: tabs <b>Edit</b>, <b>Children/Used by</b>, <b>Reading</b>. Above the editor: title, aspect tags, + Aspect button, Parent selector.',
    },
    'ch5': {
        'title': '5. Narrative Structure',
        's1_title': 'Creating the hierarchy',
        's1_text': 'The STRUCTURE section contains your story\'s text, organized as a tree. Example: Book → Part → Chapter → Scene.',
        's2_title': 'Creating new elements',
        's2_text': 'Click <b>+ New Chunk</b> in the sidebar, or <b>+ Child of...</b> to create nested elements.',
        's3_title': 'Automatic numbering',
        's3_text': 'Use macros in the title: <b>[:ORD]</b> for 1,2,3..., <b>[:ORD-ROM]</b> for I,II,III..., <b>[:ORD-ALPHA]</b> for A,B,C...',
    },
    'ch6': {
        'title': '6. Aspects',
        'intro': 'Aspects cross through the story: <b>Characters</b> (red), <b>Places</b> (green), <b>Themes</b> (orange), <b>Sequences</b> (purple), <b>Timeline</b> (blue).',
        'text': 'Each aspect can have sub-elements. To create one, click <b>+ New aspect</b> in the sidebar.',
    },
    'ch7': {
        'title': '7. Connections',
        's1_title': 'The [@id] syntax',
        's1_text': 'Write <b>[@id]</b> in the text to link a scene to an aspect. Example: [@elena] to indicate Elena appears in that scene.',
        's2_title': 'Alternative method: tags',
        's2_text': 'Click <b>+ Aspect</b> above the editor and choose from the menu. Tags appear below the title.',
        's3_title': 'The "Used by" tab',
        's3_text': 'When selecting an aspect, you see all scenes that reference it.',
    },
    'ch8': {
        'title': '8. Annotations',
        'intro': 'Leave notes for yourself: <b>TODO</b> (things to do), <b>NOTE</b> (notes), <b>FIX</b> (problems).',
        's1_title': 'Creating annotations',
        's1_text': 'Select text, right-click, choose the type. Syntax: <b>[!TODO:text:priority:comment]</b>',
        's2_title': 'The Annotations panel',
        's2_text': 'In the sidebar, ANNOTATIONS section shows all annotations grouped. Clicking jumps to that point in the text.',
        'note': '<i>Annotations do NOT appear in PDF export.</i>',
    },
    'ch9': {
        'title': '9. Search and replace',
        's1_title': 'Global filter',
        's1_text': 'The field at the top of the sidebar filters the entire project. Supports <b>[Aa]</b> for case sensitivity and <b>[.*]</b> for regex.',
        's2_title': 'Local search',
        's2_text': 'Press <b>Ctrl+F</b> to search in the current chunk. Use the ‹ › arrows to navigate between matches.',
        's3_title': 'Replace',
        's3_text': 'Press <b>Ctrl+H</b> to open replace. <b>Replace</b> changes current match, <b>Replace all</b> changes all occurrences.',
    },
    'ch10': {
        'title': '10. Radial map',
        'intro': 'Graphical visualization: structure in center, aspects around it, colored lines for connections.',
        's1_title': 'Interaction',
        's1_text': 'Scroll to zoom, click to select, hover for details in the bottom-left panel.',
    },
    'ch11': {
        'title': '11. PDF Export',
        's1_title': 'How to export',
        's1_text': 'Click <b>Export → PDF</b>. Includes title page, chapters with page breaks, scenes separated by ***.',
        's2_title': 'What\'s excluded',
        's2_text': 'YAML frontmatter, [@id] references, annotations, metadata, aspect containers.',
    },
    'ch12': {
        'title': '12. Settings',
        's1_title': 'Themes',
        's1_text': 'Four themes: <b>Tessuto</b> (default), <b>Dark</b>, <b>Light</b>, <b>Sepia</b>.',
        's2_title': 'Other options',
        's2_text': 'Autosave delay (1-10 sec), custom colors, language (Italian/English), import/export settings.',
    },
    'ch13': {
        'title': '13. The .trmd file format',
        'intro': 'Text file with: <b>YAML frontmatter</b> (metadata between ---) and <b>Content</b> (chunks with indentation).',
        's1_title': 'Chunk syntax',
        's1_code': '[C:id"Title"][@aspect1][@aspect2]\nContent...\n\n  [C:child"Child title"]\n  Child content (2 spaces indent)',
        's2_title': 'Reserved IDs',
        's2_text': 'personaggi, luoghi, temi, sequenze, timeline',
    },
    'ch14': {
        'title': '14. Keyboard shortcuts',
        'intro': 'Ctrl/Cmd+Z (Undo), Ctrl/Cmd+Shift+Z (Redo), Escape (close), Ctrl/Cmd+F (search), Ctrl/Cmd+H (replace), Ctrl/Cmd+Shift+F (global filter), ↑/↓ (navigate results).',
        'note': '<i>Cmd is for macOS, Ctrl for Windows/Linux.</i>',
    },
    'appendix': {
        'title': 'Appendix: Quick reference',
        'footer': '<i>Tramando — Weave your story</i>',
    },
}

# =============================================================================
# BUILD MANUAL
# =============================================================================
def build_manual(lang):
    T = IT if lang == 'it' else EN
    styles = create_styles()
    
    doc = SimpleDocTemplate(T['filename'], pagesize=A4, leftMargin=MARGIN, rightMargin=MARGIN, topMargin=MARGIN, bottomMargin=MARGIN)
    story = []
    
    # COVER
    story.append(Spacer(1, 4*cm))
    story.append(Paragraph("Tramando", styles['CoverTitle']))
    story.append(Paragraph(T['tagline'], styles['CoverSubtitle']))
    story.append(Spacer(1, 0.5*cm))
    story.append(Paragraph(T['manual_title'], styles['CoverSubtitle']))
    story.append(Spacer(1, 1*cm))
    add_image_if_exists(story, lang, 'splash.png', '', styles, width=12*cm, height=8*cm)
    story.append(Spacer(1, 1*cm))
    story.append(Paragraph(T['version'], styles['Body']))
    story.append(PageBreak())
    
    # TOC
    story.append(Paragraph(T['toc_title'], styles['ChapterTitle']))
    for ch in T['chapters']:
        story.append(Paragraph(ch, styles['Body']))
    story.append(PageBreak())
    
    # CH1: Introduction
    ch = T['ch1']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_text'], styles['Body']))
    story.append(Paragraph(ch['s1_text2'], styles['Body']))
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_text'], styles['Body']))
    story.append(Paragraph(ch['s2_text2'], styles['Body']))
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_text'], styles['Body']))
    story.append(PageBreak())
    
    # CH2: Getting started
    ch = T['ch2']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_text'], styles['Body']))
    story.append(Paragraph(ch['s1_options'], styles['Body']))
    add_image_if_exists(story, lang, 'splash.png', T['captions']['splash'], styles, width=13*cm, height=8*cm)
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_text'], styles['Body']))
    story.append(Paragraph(ch['s2_areas'], styles['Body']))
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_text'], styles['Body']))
    story.append(Paragraph(ch['s3_text2'], styles['Body']))
    story.append(Paragraph(ch['s3_tip'], styles['Note']))
    story.append(PageBreak())
    
    # CH3: Markup
    ch = T['ch3']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['intro'], styles['Body']))
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_text'], styles['Body']))
    story.append(Paragraph(ch['s1_text2'], styles['Body']))
    story.append(Paragraph(f"<font face='Courier'>{ch['s1_code']}</font>", styles['CodeBlock']))
    story.append(Paragraph(ch['s1_result'], styles['Body']))
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_text'], styles['Body']))
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_text'], styles['Body']))
    story.append(Paragraph(ch['s4_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s4_text'], styles['Body']))
    story.append(Paragraph(ch['s4_tip'], styles['Note']))
    story.append(PageBreak())
    
    # CH4: Interface
    ch = T['ch4']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    add_image_if_exists(story, lang, 'main.png', T['captions']['main'], styles, width=16*cm, height=10*cm)
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_text'], styles['Body']))
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_text'], styles['Body']))
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_text'], styles['Body']))
    story.append(PageBreak())
    
    # CH5: Structure
    ch = T['ch5']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_text'], styles['Body']))
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_text'], styles['Body']))
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_text'], styles['Body']))
    story.append(PageBreak())
    
    # CH6: Aspects
    ch = T['ch6']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['intro'], styles['Body']))
    story.append(Paragraph(ch['text'], styles['Body']))
    story.append(PageBreak())
    
    # CH7: Connections
    ch = T['ch7']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_text'], styles['Body']))
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_text'], styles['Body']))
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_text'], styles['Body']))
    story.append(PageBreak())
    
    # CH8: Annotations
    ch = T['ch8']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['intro'], styles['Body']))
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_text'], styles['Body']))
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_text'], styles['Body']))
    story.append(Paragraph(ch['note'], styles['Note']))
    story.append(PageBreak())
    
    # CH9: Search and replace
    ch = T['ch9']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    add_image_if_exists(story, lang, 'filter.png', T['captions']['filter'], styles, width=14*cm)
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_text'], styles['Body']))
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_text'], styles['Body']))
    story.append(Paragraph(ch['s3_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s3_text'], styles['Body']))
    story.append(PageBreak())
    
    # CH10: Map
    ch = T['ch10']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['intro'], styles['Body']))
    add_image_if_exists(story, lang, 'map.png', T['captions']['map'], styles, width=14*cm)
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_text'], styles['Body']))
    story.append(PageBreak())
    
    # CH11: Export
    ch = T['ch11']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_text'], styles['Body']))
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_text'], styles['Body']))
    story.append(PageBreak())
    
    # CH12: Settings
    ch = T['ch12']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    add_image_if_exists(story, lang, 'settings.png', T['captions']['settings'], styles, width=8*cm)
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s1_text'], styles['Body']))
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(ch['s2_text'], styles['Body']))
    story.append(PageBreak())
    
    # CH13: File format
    ch = T['ch13']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['intro'], styles['Body']))
    story.append(Paragraph(ch['s1_title'], styles['SectionTitle']))
    story.append(Paragraph(f"<font face='Courier'>{ch['s1_code']}</font>", styles['CodeBlock']))
    story.append(Paragraph(ch['s2_title'], styles['SectionTitle']))
    story.append(Paragraph(f"<font face='Courier'>{ch['s2_text']}</font>", styles['CodeBlock']))
    story.append(PageBreak())
    
    # CH14: Shortcuts
    ch = T['ch14']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Paragraph(ch['intro'], styles['Body']))
    story.append(Paragraph(ch['note'], styles['Note']))
    story.append(PageBreak())
    
    # Appendix
    ch = T['appendix']
    story.append(Paragraph(ch['title'], styles['ChapterTitle']))
    story.append(Spacer(1, 2*cm))
    story.append(Paragraph(ch['footer'], styles['Caption']))
    
    # Build
    doc.build(story)
    print(f"✓ Generato: {T['filename']}")


def main():
    args = sys.argv[1:] if len(sys.argv) > 1 else ['it', 'en']
    
    print("Tramando - Generatore Manuale")
    print("=" * 40)
    
    for lang in args:
        if lang in ['it', 'en']:
            print(f"\nGenerazione manuale {lang.upper()}...")
            build_manual(lang)
        else:
            print(f"[!] Lingua non supportata: {lang}")
    
    print("\n✓ Completato!")


if __name__ == "__main__":
    main()