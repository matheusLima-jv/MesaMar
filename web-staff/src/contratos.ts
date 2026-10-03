import { booleano, centavos, conferir, data, hora, inteiro, lista, metodo, nulo, objeto, opcional, produto, secoes, statusConta, statusPedido, texto, uuid, type Regra } from '../../web-shared/src/validacao'
export const sessaoRegra = objeto({ logado: booleano, csrf: texto(500), usuario: opcional(nulo(objeto({ id: uuid, nome: texto(100, 1), papel: v => v === 'DONO' || v === 'ATENDENTE' }))), barraca: opcional(nulo(objeto({ nome: texto(120, 1), codigo: texto(100, 1), praia: texto(100, 1), demo: opcional(booleano) }))) })
export const comandaRegra = objeto({ id: uuid, numeroChegada: inteiro(0), nome: texto(100, 1), guardaSol: nulo(inteiro(1)), whatsapp: nulo(texto(25)), whatsappExibicao: nulo(texto(30)), link: v => typeof v === 'string' && v.length < 2000 && /^https?:\/\/[^/]+\/c\/[A-Za-z0-9_-]{22,64}$/.test(v), hora, status: statusConta })
const itemFila = objeto({ id: uuid, nome: texto(120, 1), quantidade: inteiro(1, 20), observacao: nulo(texto(80)), cancelado: booleano, motivoCancelamento: nulo(texto(500)) })
const pedidoFila = objeto({ id: uuid, numero: inteiro(1), status: statusPedido, criadoEm: data, guardaSol: nulo(inteiro(1)), cliente: texto(100, 1), itens: lista(itemFila, 30, 1, 'id') })
const conta = objeto({ comandaId: uuid, cliente: texto(100, 1), guardaSol: nulo(inteiro(1)), status: statusConta, pedidoCentavos: centavos, pagoCentavos: centavos, saldoCentavos: centavos, metodo: nulo(metodo), trocoParaCentavos: nulo(centavos), avisoEm: nulo(data), abertaEm: data, pedidosAguardando: inteiro(0), saldoPerdoadoCentavos: nulo(centavos) })
export function contrato<T> (path: string, method: string, dados: unknown): T {
  const caminho = path.split('?')[0]
  const regra: Regra = caminho === '/sessao' || caminho.startsWith('/entrar/') ? sessaoRegra
    : caminho === '/cardapio' ? secoes
      : caminho.endsWith('/atendentes') ? lista(objeto({ id: uuid, nome: texto(100, 1) }), 200, 0, 'id')
        : caminho === '/guarda-sois' ? lista(objeto({ id: uuid, numero: inteiro(1), clientes: inteiro(0) }), 1000, 0, 'id')
          : caminho === '/clientes' ? (method === 'GET' ? lista(comandaRegra, 200, 0, 'id') : comandaRegra)
            : caminho.endsWith('/novo-qr') ? comandaRegra
              : caminho.endsWith('/esgotado') ? produto
                : caminho === '/fila' ? objeto({ agora: data, tempoAlertaMin: inteiro(1, 1440), pedidosPausados: booleano, mensagemPausa: nulo(texto(1000)), pedidos: lista(pedidoFila, 10_000, 0, 'id') })
                  : caminho === '/pagamentos' ? objeto({ pagina: inteiro(0), temMais: booleano, totalAguardando: inteiro(0), totalSaldoAbertoCentavos: centavos, agora: data, aguardando: lista(conta, 200, 0, 'comandaId'), abertas: lista(conta, 200, 0, 'comandaId') })
                    : caminho.endsWith('/pagamentos') ? objeto({ conta, pagamento: objeto({ id: uuid, metodo, valorCentavos: centavos, valorRecebidoCentavos: nulo(centavos), trocoCentavos: centavos }), fechada: booleano })
                      : caminho.startsWith('/comandas/') && caminho.endsWith('/pedidos') ? objeto({ id: uuid, numero: inteiro(1), status: statusPedido, hora, totalCentavos: centavos, excecoes: opcional(lista(texto(30, 1), 2)) })
                        : caminho.startsWith('/comandas/') ? conta
                          : caminho.startsWith('/pedidos/') ? pedidoFila
                            : caminho === '/pausa' ? objeto({ pedidosPausados: booleano, mensagemPausa: nulo(texto(1000)) })
                              : caminho === '/push' ? objeto({ habilitado: booleano, chavePublica: nulo(texto(200)) }) : objeto({})
  return conferir<T>(dados, regra)
}
