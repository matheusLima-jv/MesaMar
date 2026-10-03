import { booleano, centavos, conferir, hora, inteiro, lista, metodo, nulo, objeto, opcional, secoes, statusConta, statusPedido, texto, uuid, type Regra } from '../../web-shared/src/validacao'
const item = objeto({ id: uuid, produtoId: uuid, nome: texto(120, 1), precoCentavos: centavos, quantidade: inteiro(1, 20), observacao: nulo(texto(80)), subtotalCentavos: centavos, cancelado: booleano, motivoCancelamento: nulo(texto(500)) })
const pedido = objeto({ id: uuid, numero: inteiro(1), status: statusPedido, hora, itens: lista(item, 30, 1, 'id'), totalCentavos: centavos, motivoCancelamento: nulo(texto(500)) })
const pedidos = lista(pedido, 10_000, 0, 'id')
const conta = objeto({ status: statusConta, pedidoCentavos: centavos, pagoCentavos: centavos, saldoCentavos: centavos, metodo: nulo(metodo), trocoParaCentavos: nulo(centavos), avisadoEm: nulo(texto(40)), pix: nulo(objeto({ codigo: texto(1000, 1), chave: texto(200, 1), nomeRecebedor: texto(100, 1), cidade: texto(100, 1), valorCentavos: centavos })), aceitaPix: booleano, pedidos, pagamentos: lista(objeto({ metodo, rotulo: texto(100, 1), valorCentavos: centavos, valorRecebidoCentavos: nulo(centavos), hora }), 10_000), encerradaComSaldo: opcional(booleano) })
export function contrato<T> (caminho: string, method: string, dados: unknown): T {
  const regra: Regra = caminho === '' ? objeto({ barraca: texto(120, 1), nome: texto(100, 1), guardaSol: nulo(inteiro(1)), bloqueio: nulo(objeto({ codigo: texto(100, 1), mensagem: texto(1000, 1) })), saldoCentavos: centavos, modoPagamento: v => v === 'FINAL' || v === 'POR_PEDIDO', demo: booleano, cardapio: secoes })
    : caminho === '/pedidos' ? (method === 'GET' ? objeto({ saldoCentavos: centavos, pedidos }) : pedido)
      : caminho.startsWith('/conta') ? conta : objeto({})
  return conferir<T>(dados, regra)
}
