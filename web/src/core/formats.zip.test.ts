// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { strToU8, zipSync } from 'fflate'
import { Blob as NodeBlob } from 'node:buffer'
import { ZipArchive } from './zip'
import { prepareEpubHtml, readEpub } from './epub'
import { blocks, groupParagraphs, plainText, readDocx, styleInfo, type DocxParagraph } from './docx'

// Mesmos livros e casos de ZipFormatsTest e BookHelpersTest do app.

/** Blob de um ZIP com as entradas dadas (texto ou bytes). */
function zip(entries: Record<string, string | Uint8Array>): Promise<ZipArchive> {
  const files: Record<string, Uint8Array> = {}
  // Cópia no Uint8Array do ambiente de teste (jsdom), que é o que o fflate reconhece.
  for (const [name, body] of Object.entries(entries)) files[name] = new Uint8Array(typeof body === 'string' ? strToU8(body) : body)
  // O Blob do jsdom não tem arrayBuffer(); o do Node tem, como o do navegador.
  return ZipArchive.open(new NodeBlob([zipSync(files)]) as unknown as Blob)
}

const EPUB3: Record<string, string> = {
  mimetype: 'application/epub+zip',
  'META-INF/container.xml': `<?xml version="1.0"?>
    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
    </container>`,
  'OEBPS/content.opf': `<?xml version="1.0"?>
    <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Dom Casmurro</dc:title></metadata>
      <manifest>
        <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
        <item id="c1" href="Text/cap%201.xhtml" media-type="application/xhtml+xml"/>
        <item id="c2" href="Text/cap2.xhtml" media-type="application/xhtml+xml"/>
        <item id="extra" href="Text/notas.xhtml" media-type="application/xhtml+xml"/>
        <item id="img" href="Images/capa.jpg" media-type="image/jpeg" properties="cover-image"/>
        <item id="css" href="style.css" media-type="text/css"/>
      </manifest>
      <spine>
        <itemref idref="c1"/><itemref idref="missing"/><itemref idref="c2"/><itemref idref="extra" linear="no"/>
      </spine>
    </package>`,
  'OEBPS/nav.xhtml': `<?xml version="1.0"?>
    <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body>
      <nav epub:type="landmarks"><ol><li><a href="Text/cap2.xhtml">Errado</a></li></ol></nav>
      <nav epub:type="toc"><ol>
        <li><a href="Text/cap%201.xhtml"><span>I.</span> Do título</a></li>
        <li><a href="Text/cap2.xhtml#s1">II. Do livro</a></li>
      </ol></nav>
    </body></html>`,
  'OEBPS/Text/cap 1.xhtml': '<html><body><p>Uma noite destas, vindo da cidade para o Engenho Novo…</p></body></html>',
  'OEBPS/Text/cap2.xhtml': '<html><body><p>Agora que expliquei o título, passo a escrever o livro.</p></body></html>',
  'OEBPS/Text/notas.xhtml': '<html><body>notas</body></html>',
  'OEBPS/Images/capa.jpg': 'fake',
  'OEBPS/style.css': 'p{}'
}

const DOCX: Record<string, string> = {
  '[Content_Types].xml': '<Types/>',
  'word/styles.xml': `<?xml version="1.0"?>
    <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
      <w:style w:type="paragraph" w:styleId="Ttulo1"><w:name w:val="heading 1"/></w:style>
      <w:style w:type="paragraph" w:styleId="Ttulo"><w:name w:val="Title"/></w:style>
    </w:styles>`,
  'word/document.xml': `<?xml version="1.0"?>
    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
      <w:p><w:pPr><w:pStyle w:val="Ttulo"/></w:pPr><w:r><w:t>Relatório</w:t></w:r></w:p>
      <w:p><w:pPr><w:pStyle w:val="Ttulo1"/></w:pPr><w:r><w:t>Introdução</w:t></w:r></w:p>
      <w:p>
        <w:r><w:t xml:space="preserve">Texto </w:t></w:r>
        <w:r><w:rPr><w:b/></w:rPr><w:t>negrito</w:t></w:r>
        <w:r><w:rPr><w:b w:val="0"/><w:i/></w:rPr><w:t xml:space="preserve"> e itálico</w:t></w:r>
        <w:r><w:tab/><w:t>fim</w:t></w:r>
      </w:p>
      <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/></w:numPr></w:pPr><w:r><w:t>primeiro item</w:t></w:r></w:p>
      <w:p></w:p>
      <w:tbl><w:tr><w:tc><w:p><w:r><w:t>célula</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
      <w:p><w:r><w:t>antes</w:t></w:r><w:r><w:pict><w:txbxContent><w:p><w:r><w:t>caixa</w:t></w:r></w:p></w:txbxContent></w:pict></w:r><w:r><w:t>depois</w:t></w:r></w:p>
    </w:body></w:document>`
}

function imageOnlyDocx(extra = ''): Record<string, string | Uint8Array> {
  return {
    '[Content_Types].xml': '<Types/>',
    'word/_rels/document.xml.rels': `<?xml version="1.0"?>
      <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
        <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
        <Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://exemplo.com" TargetMode="External"/>
      </Relationships>`,
    'word/document.xml': `<?xml version="1.0"?>
      <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
          xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
          xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
          xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>
        <w:p><w:pPr><w:pStyle w:val="NormalWeb"/></w:pPr><w:r><w:drawing><wp:inline><a:graphic><a:graphicData>
          <pic:pic><pic:blipFill><a:blip r:embed="rId4"/></pic:blipFill></pic:pic>
        </a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
        ${extra}
        <w:sectPr/>
      </w:body></w:document>`,
    'word/media/image1.png': new Uint8Array([0x89, 0x50, 0x4e, 0x47])
  }
}

describe('EPUB', () => {
  it('EPUB 3: ordem de leitura, títulos do sumário e capa', async () => {
    const book = await readEpub(await zip(EPUB3))
    expect(book.title).toBe('Dom Casmurro')
    expect(book.chapters).toEqual(['OEBPS/Text/cap 1.xhtml', 'OEBPS/Text/cap2.xhtml'])
    expect(book.chapterTitles).toEqual(['I. Do título', 'II. Do livro'])
    expect(book.coverPath).toBe('OEBPS/Images/capa.jpg')
  })

  it('EPUB 2: sumário NCX e capa pelo meta', async () => {
    const entries = { ...EPUB3 }
    delete entries['OEBPS/nav.xhtml']
    entries['OEBPS/content.opf'] = `<?xml version="1.0"?>
      <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
        <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title> </dc:title><meta name="cover" content="img"/></metadata>
        <manifest>
          <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
          <item id="c1" href="Text/cap%201.xhtml" media-type="application/xhtml+xml"/>
          <item id="img" href="Images/capa.jpg" media-type="image/jpeg"/>
        </manifest>
        <spine toc="ncx"><itemref idref="c1"/></spine>
      </package>`
    entries['OEBPS/toc.ncx'] = `<?xml version="1.0"?>
      <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
        <navPoint id="n1"><navLabel><text>Capítulo Um</text></navLabel><content src="Text/cap%201.xhtml"/></navPoint>
      </navMap></ncx>`
    const book = await readEpub(await zip(entries))
    expect(book.title).toBeNull()
    expect(book.chapterTitles).toEqual(['Capítulo Um'])
    expect(book.coverPath).toBe('OEBPS/Images/capa.jpg')
  })

  it('sem container falha', async () => {
    await expect(readEpub(await zip({ 'a.txt': 'x' }))).rejects.toThrow()
  })

  it('HTML do capítulo ganha a política primeiro e o estilo por último', () => {
    const html = '<html><head><script>alert(1)</script><title>x</title></head><body><header>h</header></body></html>'
    const out = prepareEpubHtml(html, 'body{}')
    const policy = out.indexOf('Content-Security-Policy')
    expect(policy).toBeGreaterThanOrEqual(0)
    expect(policy).toBeLessThan(out.indexOf('<script>'))
    const style = out.indexOf('<style>body{}</style>')
    expect(style).toBeGreaterThan(out.indexOf('<title>'))
    expect(style).toBeLessThan(out.indexOf('</head>'))
    expect(out).toContain('<header>h</header>')
    const noHead = prepareEpubHtml('<html xmlns="x"><body>oi</body></html>', 'p{}')
    expect(noHead.startsWith('<html xmlns="x"><head><meta http-equiv="Content-Security-Policy"')).toBe(true)
    expect(noHead.endsWith('<body>oi</body></html>')).toBe(true)
  })
})

describe('DOCX', () => {
  it('parágrafos, títulos, listas e estilos', async () => {
    const paragraphs = await readDocx(await zip(DOCX))
    expect(paragraphs.map((p) => p.text)).toEqual(['Relatório', 'Introdução', 'Texto negrito e itálico\tfim', 'primeiro item', 'célula', 'antes\ncaixa\ndepois'])
    expect(paragraphs[0].kind).toBe('TITLE')
    expect(paragraphs[1].kind).toBe('HEADING1')
    expect(paragraphs[3].kind).toBe('LIST_ITEM')
    const styles = paragraphs[2].styles
    expect(styles.length).toBe(2)
    expect(paragraphs[2].text.slice(styles[0].start, styles[0].end)).toBe('negrito')
    expect(styles[0].bold && !styles[0].italic).toBe(true)
    expect(!styles[1].bold && styles[1].italic).toBe(true)
  })

  it('imagens pelas relações (DrawingML e VML)', async () => {
    const extra = `<w:p><w:r><w:t>Antes</w:t></w:r><w:r><w:pict><v:shape xmlns:v="urn:schemas-microsoft-com:vml"><v:imagedata r:id="rId4"/></v:shape></w:pict></w:r><w:r><w:t> depois</w:t></w:r></w:p>
      <w:p><w:r><w:drawing><a:blip r:embed="rIdQueNaoExiste"/></w:drawing></w:r></w:p>`
    const paragraphs = await readDocx(await zip(imageOnlyDocx(extra)))
    expect(paragraphs.length).toBe(2)
    expect(paragraphs[0].text).toBe('')
    expect(paragraphs[0].images).toEqual(['word/media/image1.png'])
    expect(paragraphs[1].text).toBe('Antes depois')
    expect(paragraphs[1].images).toEqual(['word/media/image1.png'])
  })

  it('agrupa em blocos, títulos começam bloco novo', () => {
    const p = (text: string, kind: DocxParagraph['kind'] = 'NORMAL'): DocxParagraph => ({ text, kind, styles: [], images: [] })
    const groups = groupParagraphs([p('Título', 'TITLE'), p('a'.repeat(30)), p('b'.repeat(30)), p('Seção', 'HEADING1'), p('item', 'LIST_ITEM')], 50)
    expect(groups.map((g) => g.map((x) => x.text))).toEqual([['Título', 'a'.repeat(30)], ['b'.repeat(30)], ['Seção', 'item']])
    expect(plainText(groups.at(-1)!)).toBe('Seção\n\n•  item')
  })

  it('layout do documento: figuras com tamanho e lado, tabela com bordas e mesclas, alinhamento e tamanho da letra', () => {
    const ns = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"'
    const anchor = (id: string, x: number, cx: number, vRel = 'paragraph', y = 0) =>
      `<w:r><w:drawing><wp:anchor behindDoc="0"><wp:positionH relativeFrom="page"><wp:posOffset>${x}</wp:posOffset></wp:positionH><wp:positionV relativeFrom="${vRel}"><wp:posOffset>${y}</wp:posOffset></wp:positionV><wp:extent cx="${cx}" cy="499024"/><a:graphic><a:blip r:embed="${id}"/></a:graphic></wp:anchor></w:drawing></w:r>`
    const xml = `<w:document ${ns}><w:body>
      <w:p>${anchor('rId1', 767470, 1304218)}${anchor('rId2', 5896355, 1523621)}${anchor('rId3', 0, 7560073, 'page', 10096500)}</w:p>
      <w:tbl><w:tblPr><w:tblBorders><w:top w:val="single"/><w:insideV w:val="single"/></w:tblBorders></w:tblPr>
        <w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="1000"/></w:tblGrid>
        <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>Título da tabela</w:t></w:r></w:p></w:tc></w:tr>
        <w:tr><w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>Curso</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc></w:tr>
        <w:tr><w:tc><w:tcPr><w:vMerge/></w:tcPr><w:p/></w:tc><w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc></w:tr>
      </w:tbl>
      <w:p><w:pPr><w:jc w:val="both"/><w:ind w:left="480" w:firstLine="240"/><w:spacing w:before="240" w:after="0"/></w:pPr><w:r><w:rPr><w:sz w:val="36"/></w:rPr><w:t>Grande</w:t></w:r><w:r><w:t> normal</w:t></w:r><w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:t>1</w:t></w:r></w:p>
      <w:sectPr><w:pgSz w:w="11910" w:h="16840"/><w:pgMar w:left="1701"/></w:sectPr>
    </w:body></w:document>`
    const relations = new Map([['rId1', 'word/media/a.png'], ['rId2', 'word/media/b.png'], ['rId3', 'word/media/c.png']])
    const list = blocks(xml, { levels: new Map(), defaultSize: 24 }, relations)
    expect(list.map((b) => b.type)).toEqual(['p', 'table', 'p'])
    const figures = list[0].type === 'p' ? list[0].p.figures! : []
    expect(figures.map((f) => [f.side, f.place, f.width])).toEqual([['left', 'inline', 137], ['right', 'inline', 160], ['center', 'end', 794]])
    const table = list[1].type === 'table' ? list[1].table : null
    expect(table!.borders).toBe(true)
    expect(table!.columns).toEqual([0.75, 0.25])
    expect(table!.rows.map((r) => r.map((c) => [c.span, c.rowSpan]))).toEqual([[[2, 1]], [[1, 2], [1, 1]], [[1, 1]]])
    const title = table!.rows[0][0].blocks[0]
    expect(title.type === 'p' && title.p.align).toBe('center')
    const p = list[2].type === 'p' ? list[2].p : null
    expect([p!.align, p!.indent, p!.firstLine, p!.before, p!.after]).toEqual(['justify', 2, 1, 1, undefined])
    expect(p!.styles.map((s) => [p!.text.slice(s.start, s.end), s.size, s.vert])).toEqual([['Grande', 1.5, undefined], ['1', undefined, 'sup']])
  })

  it('tamanho padrão da letra vem do documento', () => {
    const w = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
    expect(styleInfo(`<w:styles ${w}><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults></w:styles>`).defaultSize).toBe(22)
    expect(styleInfo(`<w:styles ${w}><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:rPr><w:sz w:val="24"/></w:rPr></w:style></w:styles>`).defaultSize).toBe(24)
  })
})

