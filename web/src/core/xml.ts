/** XML com namespaces, via DOMParser do navegador. */
export function parseXml(text: string): Document {
  const doc = new DOMParser().parseFromString(text.replace(/^﻿/, ''), 'application/xml')
  if (doc.getElementsByTagName('parsererror').length) throw new Error('XML inválido')
  return doc
}

/** Percorre os elementos na ordem do documento, avisando a entrada e a saída de cada um. */
export function walk(node: Node, visit: { start?: (e: Element) => boolean | void; end?: (e: Element) => void; text?: (t: Text) => void }) {
  for (let child = node.firstChild; child; child = child.nextSibling) {
    if (child.nodeType === 1) {
      const element = child as Element
      // start devolvendo true pula os filhos (o elemento já foi tratado por inteiro).
      const skip = visit.start?.(element) === true
      if (!skip) walk(element, visit)
      visit.end?.(element)
    } else if (child.nodeType === 3 || child.nodeType === 4) {
      visit.text?.(child as Text)
    }
  }
}

/** Atributo sem namespace ou, se não houver, com qualquer prefixo (`opf:role`, `epub:type`). */
export function attr(e: Element, name: string): string | null {
  if (e.hasAttribute(name)) return e.getAttribute(name)
  for (const a of Array.from(e.attributes)) if (a.localName === name) return a.value
  return null
}
