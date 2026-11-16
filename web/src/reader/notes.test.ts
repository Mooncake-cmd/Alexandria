// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { describeLink, linkLooksLikeNote, nearestLink, noteContent } from './notes'
import { looksLikeNoteMark } from '../core/links'

// Trecho do Moby Dick (EPUB da Zahar/Calibre) que motivou a mudança: chamadas em <sup> e notas em <aside>.
const CHAPTER = `<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>x</title></head>
<body><section epub:type="chapter">
<p class="indent">Com um floreio filosófico, Catão se atira sobre a espada; eu subo, manso, a bordo de um navio.<sup><a id="footnote-198-backlink" href="../Text/part0012_split_001.html#footnote-198">1</a></sup> Nada surpreendente nisso.</p>
<p>Uma dose concentrada de <i>Sêneca</i>.<sup><a id="footnote-194-backlink" href="part.html#footnote-194">5</a></sup></p>
<p><a href="cap2.html">Capítulo 2</a></p>
<aside epub:type="footnote" id="footnote-198">
<p class="fn"><a epub:type="noteref" href="../Text/part0012_split_001.html#footnote-198-backlink">1</a>. Marco Pórcio Catão, ou Catão, o <i>Jovem</i> (95–46 a.C.).</p>
</aside>
<aside epub:type="footnote" id="footnote-194">
<p class="fn"><a epub:type="noteref" href="#footnote-194-backlink">5</a>. O estoicismo… Sêneca (4 a.C.–65 d.C.).</p>
</aside>
</section></body></html>`

const parse = (text: string, type: DOMParserSupportedType = 'application/xhtml+xml') => new DOMParser().parseFromString(text, type)

describe('notas de rodapé', () => {
  it('a chamada sobrescrita leva ao texto da nota, com itálico e sem o resto do capítulo', () => {
    const doc = parse(CHAPTER)
    const hit = describeLink(doc.getElementById('footnote-198-backlink')!)
    expect(hit.raised).toBe(true)
    expect(hit.inNote).toBe(false)
    expect(linkLooksLikeNote(hit, looksLikeNoteMark)).toBe(true)
    const note = noteContent(doc.getElementById('footnote-198'), true, false)!
    expect(note.text).toBe('1. Marco Pórcio Catão, ou Catão, o Jovem (95–46 a.C.).')
    expect(note.html).toBe('1. Marco Pórcio Catão, ou Catão, o <i>Jovem</i> (95–46 a.C.).')
  })

  it('o número dentro da nota é o caminho de volta, não outra nota', () => {
    const doc = parse(CHAPTER)
    const back = describeLink(doc.querySelector('#footnote-198 a')!)
    expect(back.noteref).toBe(true)
    expect(back.inNote).toBe(true)
    expect(linkLooksLikeNote(back, looksLikeNoteMark)).toBe(false)
  })

  it('links comuns não viram nota; notas sem marcação só quando o link parecia uma chamada', () => {
    const doc = parse(CHAPTER)
    const chapterLink = describeLink(doc.querySelector('a[href="cap2.html"]')!)
    expect(linkLooksLikeNote(chapterLink, looksLikeNoteMark)).toBe(false)
    const plain = parse('<html><body><h1 id="t">Título</h1><ol><li id="n1"><p>Nota em <b>outro</b> arquivo.</p></li></ol></body></html>', 'text/html')
    expect(noteContent(plain.getElementById('n1'), true, false)?.html).toBe('Nota em <b>outro</b> arquivo.')
    expect(noteContent(plain.getElementById('n1'), false, false)).toBeNull()
    // Arquivo fora da ordem de leitura, sem âncora: mostra o arquivo inteiro.
    expect(noteContent(plain.body, false, true)?.html).toBe('Título<br>Nota em <b>outro</b> arquivo.')
  })

  it('o HTML da nota não leva scripts nem atributos', () => {
    const doc = parse('<html><body><aside epub:type="footnote" id="n"><p onclick="x()">Texto <img src=x onerror="y()"><script>z()</script><span style="color:red">ok</span></p></aside></body></html>', 'text/html')
    expect(noteContent(doc.getElementById('n'), false, false)?.html).toBe('Texto ok')
  })

  it('o clique direto num link vale mesmo sem medir a tela', () => {
    const doc = parse(CHAPTER)
    const target = doc.getElementById('footnote-194-backlink')!
    expect(nearestLink(doc, target, 0, 0, 10)?.href).toBe('part.html#footnote-194')
    expect(nearestLink(doc, doc.body, -500, -500, 10)).toBeNull()
  })
})
