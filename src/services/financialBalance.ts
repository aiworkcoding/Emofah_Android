export type ClassificationStatus =
  | 'PENDING_CLASSIFICATION'
  | 'BUSINESS_PRODUCT'
  | 'BUSINESS_SERVICE'
  | 'NON_BUSINESS';

export type TransactionDirection = 'INFLOW' | 'OUTFLOW' | 'TRANSFER';

export interface SharedFinancialTransaction {
  id: string;
  clientEventId: string | null;
  eventType: string;
  direction: TransactionDirection;
  amount: number;
  currency: string;
  classificationStatus: ClassificationStatus;
  referenceCode: string | null;
  channel: string | null;
  counterparty: string | null;
  counterpartyAccount: string | null;
  transactionCost: number | null;
  newBalance: number | null;
  occurredText: string | null;
  rawEvidence: string | null;
  parseStatus: string | null;
  source: string;
  actorId: string | null;
  deviceId: string | null;
  notes: string | null;
  occurredAt: string;
  createdAt: string;
  updatedAt: string | null;
  linkedSaleId: number | null;
  linkedClientSaleId: string | null;
  linkedServiceId: string | null;
  linkedClientServiceId: string | null;
  linkedProductId: number | null;
  linkedProductName: string | null;
  linkedQuantity: number | null;
  classificationNote: string | null;
  classifiedAt: string | null;
  classifiedBy: string | null;
  classifiedDevice: string | null;
  excludesFromBalance: boolean;
  serviceUnitPrice: number | null;
  serviceDiscountAmount: number | null;
  serviceTotalRevenue: number | null;
  serviceType: string | null;
  serviceLabel: string | null;
  saleTotalPrice: number | null;
  saleQtySold: number | null;
}

export interface RecentSaleOption {
  saleId: number;
  productId: number;
  productName: string;
  qtySold: number;
  totalPrice: number;
  saleDate: string;
  alreadyLinkedEventId: string | null;
}

export interface ClassificationHistoryEntry {
  id: string;
  eventId: string;
  previousStatus: string | null;
  newStatus: string;
  action: string;
  linkedProductId: number | null;
  linkedSaleId: number | null;
  linkedServiceId: string | null;
  note: string | null;
  actorId: string | null;
  deviceId: string | null;
  metadata: Record<string, unknown>;
  createdAt: string;
}

export interface BusinessBalanceSummary {
  businessBalance: number;
  businessProductNet: number;
  businessProductCount: number;
  businessServiceNet: number;
  businessServiceCount: number;
  pendingCount: number;
  pendingExcludedAmount: number;
  nonBusinessCount: number;
  nonBusinessExcludedAmount: number;
}

export interface ServiceOperationOption {
  code: string;
  label: string;
  unitLabel: string;
  defaultUnitPrice: number;
}

export const SERVICE_OPERATION_OPTIONS: ServiceOperationOption[] = [
  { code: 'PRINTING', label: 'Printing', unitLabel: 'pages', defaultUnitPrice: 10 },
  { code: 'PHOTOCOPYING', label: 'Photocopying', unitLabel: 'pages', defaultUnitPrice: 5 },
  { code: 'SCANNING', label: 'Scanning', unitLabel: 'pages', defaultUnitPrice: 20 },
  { code: 'INTERNET_BROWSING', label: 'Internet / Cyber', unitLabel: 'sessions', defaultUnitPrice: 60 },
  { code: 'EGOV_ASSISTANCE', label: 'eCitizen / Gov Assist', unitLabel: 'requests', defaultUnitPrice: 150 },
  { code: 'LAMINATION_BINDING', label: 'Lamination / Binding', unitLabel: 'items', defaultUnitPrice: 50 },
  { code: 'DOCUMENT_TYPING', label: 'Document Typing', unitLabel: 'pages', defaultUnitPrice: 50 },
  { code: 'OTHER_SERVICE', label: 'Other Service', unitLabel: 'units', defaultUnitPrice: 100 },
];

export function normalizeClassificationStatus(
  rawStatus: string | null | undefined,
  excludesFromBalance?: boolean
): ClassificationStatus {
  if (excludesFromBalance) {
    return 'NON_BUSINESS';
  }
  const normalized = (rawStatus || '').trim().toUpperCase();
  switch (normalized) {
    case 'BUSINESS_PRODUCT':
      return 'BUSINESS_PRODUCT';
    case 'BUSINESS_SERVICE':
      return 'BUSINESS_SERVICE';
    case 'NON_BUSINESS':
    case 'REMOVED':
    case 'PERSONAL':
      return 'NON_BUSINESS';
    case 'PENDING_CLASSIFICATION':
    case 'PENDING_DESKTOP_RULES':
    case 'UNCLASSIFIED':
    case '':
    default:
      return 'PENDING_CLASSIFICATION';
  }
}

export function normalizeDirection(raw: string | null | undefined): TransactionDirection {
  const upper = (raw || '').trim().toUpperCase();
  if (upper === 'OUTFLOW') return 'OUTFLOW';
  if (upper === 'TRANSFER') return 'TRANSFER';
  return 'INFLOW';
}

export function canClassifyTransaction(tx: Pick<SharedFinancialTransaction, 'classificationStatus' | 'excludesFromBalance'>): boolean {
  return normalizeClassificationStatus(tx.classificationStatus, tx.excludesFromBalance) === 'PENDING_CLASSIFICATION';
}

export function computeTransactionBusinessContribution(tx: SharedFinancialTransaction): number {
  const status = normalizeClassificationStatus(tx.classificationStatus, tx.excludesFromBalance);
  if (status !== 'BUSINESS_PRODUCT' && status !== 'BUSINESS_SERVICE') {
    return 0;
  }

  const fee = Number(tx.transactionCost) > 0 ? Number(tx.transactionCost) : 0;
  const amount = Number(tx.amount) || 0;

  if (tx.direction === 'TRANSFER') {
    return 0;
  }

  if (tx.direction === 'OUTFLOW') {
    return -(amount + fee);
  }

  if (status === 'BUSINESS_SERVICE') {
    const netService =
      tx.serviceTotalRevenue !== null && Number.isFinite(tx.serviceTotalRevenue)
        ? Number(tx.serviceTotalRevenue)
        : amount;
    return netService - fee;
  }

  const netProduct =
    tx.saleTotalPrice !== null && Number.isFinite(tx.saleTotalPrice)
      ? Number(tx.saleTotalPrice)
      : amount;
  return netProduct - fee;
}

export function computeBusinessBalanceSummary(
  transactions: SharedFinancialTransaction[]
): BusinessBalanceSummary {
  const seenKeys = new Set<string>();
  let businessProductNet = 0;
  let businessProductCount = 0;
  let businessServiceNet = 0;
  let businessServiceCount = 0;
  let pendingCount = 0;
  let pendingExcludedAmount = 0;
  let nonBusinessCount = 0;
  let nonBusinessExcludedAmount = 0;

  for (const tx of transactions) {
    const dedupKey = tx.id || tx.clientEventId || tx.referenceCode || `${tx.occurredAt}-${tx.amount}`;
    if (seenKeys.has(dedupKey)) continue;
    seenKeys.add(dedupKey);

    const status = normalizeClassificationStatus(tx.classificationStatus, tx.excludesFromBalance);
    const amount = Number(tx.amount) || 0;

    if (status === 'PENDING_CLASSIFICATION') {
      pendingCount += 1;
      pendingExcludedAmount += amount;
      continue;
    }

    if (status === 'NON_BUSINESS') {
      nonBusinessCount += 1;
      nonBusinessExcludedAmount += amount;
      continue;
    }

    const contribution = computeTransactionBusinessContribution(tx);
    if (status === 'BUSINESS_PRODUCT') {
      businessProductCount += 1;
      businessProductNet += contribution;
    } else if (status === 'BUSINESS_SERVICE') {
      businessServiceCount += 1;
      businessServiceNet += contribution;
    }
  }

  return {
    businessBalance: Math.round((businessProductNet + businessServiceNet) * 100) / 100,
    businessProductNet: Math.round(businessProductNet * 100) / 100,
    businessProductCount,
    businessServiceNet: Math.round(businessServiceNet * 100) / 100,
    businessServiceCount,
    pendingCount,
    pendingExcludedAmount: Math.round(pendingExcludedAmount * 100) / 100,
    nonBusinessCount,
    nonBusinessExcludedAmount: Math.round(nonBusinessExcludedAmount * 100) / 100,
  };
}

export function isSaleAvailableForLinking(
  sale: RecentSaleOption,
  currentEventId?: string | null
): boolean {
  if (!sale.alreadyLinkedEventId) return true;
  if (currentEventId && sale.alreadyLinkedEventId === currentEventId) return true;
  return false;
}

export function formatKes(amount: number): string {
  const abs = Math.abs(amount);
  const formatted = abs.toLocaleString('en-KE', {
    minimumFractionDigits: abs % 1 === 0 ? 0 : 2,
    maximumFractionDigits: 2,
  });
  return amount < 0 ? `-Ksh ${formatted}` : `Ksh ${formatted}`;
}
