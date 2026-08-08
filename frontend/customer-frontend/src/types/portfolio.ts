export interface HoldingDto {
  fundId: number
  fundName: string
  quantity: number
  currentPrice: number
  value: number
}

export interface PortfolioDto {
  accountId: number
  totalValue: number
  holdings: HoldingDto[]
}
