export interface HoldingDto {
  id: number
  accountId: number
  fundId: number
  fundName: string
  fundIsin: string
  quantity: number
  averagePrice: number
  currentValue: number
  createdAt?: string
  updatedAt?: string
}

export interface PortfolioDto {
  accountId: number
  totalValue: number
  holdings: HoldingDto[]
}
