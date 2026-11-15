/** Painel de "ainda vazio", como o do app. */
export function Empty({ icon, title, message }: { icon: string; title: string; message: string }) {
  return (
    <section class="empty">
      <div class="empty-icon" aria-hidden="true">{icon}</div>
      <h3>{title}</h3>
      <p>{message}</p>
    </section>
  )
}
