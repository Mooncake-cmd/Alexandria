package com.alexandria.reader

/**
 * Links internos dos livros (EPUB e MOBI convertido): para onde levam e se o número tocado parece
 * a chamada de uma nota de rodapé. Kotlin puro, coberto por testes.
 */
object BookLinks {
    /** Destino de um link dentro do livro: arquivo do ZIP e âncora (id), se houver. */
    data class Target(val path: String, val fragment: String?)

    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val noteMark = Regex("^[\\[(]?(\\d{1,4}|[a-zA-Z]|[*†‡§¶#]{1,3})[\\])]?\\.?$")

    /**
     * Resolve [href] escrito no capítulo [fromPath]. Links externos (http:, mailto: etc.) dão null:
     * o app é offline. Um href só com "#id" aponta para o próprio capítulo.
     */
    fun resolve(fromPath: String, href: String): Target? {
        val trimmed = href.trim()
        if (trimmed.isEmpty() || scheme.containsMatchIn(trimmed)) return null
        val pathPart = trimmed.substringBefore('#')
        val fragment = trimmed.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let(::decode)
        val path = if (pathPart.isEmpty()) fromPath else ZipPaths.resolve(ZipPaths.dirOf(fromPath), pathPart)
        return Target(path, fragment)
    }

    /** "1", "[12]", "(3)", "a", "*", "†": texto típico da chamada de uma nota. */
    fun looksLikeNoteMark(text: String): Boolean = noteMark.matches(text.trim())

    private fun decode(value: String): String = try {
        java.net.URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        value
    }

    /**
     * Script instalado em cada capítulo. Impede que o WebView siga os links sozinho (o app decide:
     * nota em janela, pulo dentro do capítulo ou outro capítulo), acha o link mais perto do dedo
     * e extrai o texto de uma nota (do próprio capítulo ou de outro arquivo, passado como texto).
     */
    val SCRIPT = """
        (function(){
        if (window.alxLinks) return;
        var OPS = 'http://www.idpf.org/2007/ops';
        document.addEventListener('click', function(e){
          var a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
          if (a) e.preventDefault();
        }, true);
        function typeOf(e){
          if (!e || !e.getAttribute) return '';
          return ((e.getAttribute('epub:type') || (e.getAttributeNS && e.getAttributeNS(OPS, 'type')) || '') + ' ' + (e.getAttribute('role') || '')).toLowerCase();
        }
        function dist(r, x, y){
          var dx = x < r.left ? r.left - x : (x > r.right ? x - r.right : 0);
          var dy = y < r.top ? r.top - y : (y > r.bottom ? y - r.bottom : 0);
          return Math.sqrt(dx * dx + dy * dy);
        }
        function raised(a){
          for (var e = a, i = 0; e && i < 3 && e !== document.body; e = e.parentElement, i++) {
            if ((e.tagName || '').toLowerCase() == 'sup') return true;
            var v = getComputedStyle(e).verticalAlign;
            if (v == 'super' || v == 'top' || v == 'text-top') return true;
          }
          return false;
        }
        var KEEP = {i:1, em:1, b:1, strong:1, sup:1, sub:1, cite:1};
        var BLOCK = {p:1, div:1, li:1, dd:1, dt:1, blockquote:1, h1:1, h2:1, h3:1, h4:1, h5:1, h6:1, aside:1, section:1, tr:1};
        function esc(s){ return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
        function clean(node){
          var out = '';
          for (var c = node.firstChild; c; c = c.nextSibling) {
            if (c.nodeType == 3) { out += esc(c.data.replace(/\s+/g, ' ')); continue; }
            if (c.nodeType != 1) continue;
            var tag = (c.localName || c.tagName || '').toLowerCase();
            if (tag == 'script' || tag == 'style' || tag == 'img' || tag == 'svg') continue;
            if (tag == 'br') { out += '<br>'; continue; }
            var inner = clean(c);
            if (KEEP[tag]) out += '<' + tag + '>' + inner + '</' + tag + '>';
            else if (BLOCK[tag]) out += (out && !/<br>$/.test(out) ? '<br>' : '') + inner + '<br>';
            else out += inner;
          }
          return out;
        }
        var NOTE = /(^|\s)(footnote|endnote|rearnote|note|doc-footnote|doc-endnote)(\s|$)/;
        function noteBox(el){
          for (var e = el; e && e.nodeType == 1; e = e.parentElement) {
            var tag = (e.localName || e.tagName || '').toLowerCase();
            if (tag == 'body' || tag == 'html') return null;
            if (NOTE.test(typeOf(e))) return e;
          }
          return null;
        }
        window.alxLinks = {
          near: function(x, y, r){
            var best = null, bd = r + 0.01, links = document.querySelectorAll('a[href]');
            for (var i = 0; i < links.length; i++) {
              var rs = links[i].getClientRects();
              for (var k = 0; k < rs.length; k++) {
                var rc = rs[k];
                if (rc.width == 0 || rc.bottom < y - r || rc.top > y + r) continue;
                var d = dist(rc, x, y);
                if (d < bd) { bd = d; best = links[i]; }
              }
            }
            if (!best) return '';
            return JSON.stringify({href: best.getAttribute('href'), noteref: /noteref/.test(typeOf(best)), raised: raised(best), inNote: !!noteBox(best),
              text: (best.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 24)});
          },
          note: function(id, html, likely, force){
            var doc = html == null ? document : new DOMParser().parseFromString(html, 'text/html');
            var el = id ? doc.getElementById(id) : doc.body;
            if (!el) return '';
            var box = noteBox(el);
            if (!box && (likely || force)) {
              box = el;
              while (box && box.parentElement && !BLOCK[(box.localName || box.tagName || '').toLowerCase()]) box = box.parentElement;
              if (box && /^(body|html)$/i.test(box.localName || box.tagName || '')) box = force ? box : null;
            }
            if (!box) return '';
            var text = (box.textContent || '').replace(/\s+/g, ' ').trim();
            if (!text || (!force && text.length > 4000)) return '';
            var out = clean(box).replace(/^(\s|<br>)+|(\s|<br>)+$/g, '').replace(/(<br>){3,}/g, '<br><br>');
            if (out.length > 20000) out = esc(text.slice(0, 6000)) + '…';
            return JSON.stringify({html: out, text: text.slice(0, 6000)});
          }
        };
        })()
    """.trimIndent()
}
