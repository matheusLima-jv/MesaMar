import { h, mount } from '../dom'

/** Abas que chegam nas próximas fases: diz a verdade em vez de mostrar tela vazia. */
export function abaEmBreve (root: HTMLElement, titulo: string, texto: string): void {
  mount(root,
    h('header', { class: 'tab-head' }, h('h1', {}, titulo)),
    h('div', { class: 'empty' },
      h('p', {}, texto),
      h('p', { class: 'hint' }, 'Enquanto isso, as abas Clientes e Cardápio já funcionam.')
    )
  )
}
