import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canClassifyTransaction,
  computeBusinessBalanceSummary,
  computeTransactionBusinessContribution,
  formatKes,
  isSaleAvailableForLinking,
  normalizeClassificationStatus,
  type RecentSaleOption,
  type SharedFinancialTransaction,
} from '../financialBalance.ts';

function makeTx(overrides: Partial<SharedFinancialTransaction> = {}): SharedFinancialTransaction {
  return {
    id: 'srv-1',
    clientEventId: 'fin-mpesa-abc123',
    eventType: 'MPESA_SMS_EVENT',
    direction: 'INFLOW',
    amount: 1000,
    currency: 'KES',
    classificationStatus: 'PENDING_CLASSIFICATION',
    referenceCode: 'ABC123',
    channel: 'MPESA_PAYBILL',
    counterparty: 'JANE DOE',
    counterpartyAccount: '0712345678',
    transactionCost: null,
    newBalance: null,
    occurredText: null,
    rawEvidence: 'Confirmed...',
    parseStatus: 'VALID_PARSED',
    source: 'MPESA_SMS',
    actorId: 'staff_1',
    deviceId: 'android_1',
    notes: null,
    occurredAt: '2026-11-23T09:00:00.000Z',
    createdAt: '2026-11-23T09:00:01.000Z',
    updatedAt: null,
    linkedSaleId: null,
    linkedClientSaleId: null,
    linkedServiceId: null,
    linkedClientServiceId: null,
    linkedProductId: null,
    linkedProductName: null,
    linkedQuantity: null,
    classificationNote: null,
    classifiedAt: null,
    classifiedBy: null,
    classifiedDevice: null,
    excludesFromBalance: false,
    serviceUnitPrice: null,
    serviceDiscountAmount: null,
    serviceTotalRevenue: null,
    serviceType: null,
    serviceLabel: null,
    saleTotalPrice: null,
    saleQtySold: null,
    ...overrides,
  };
}

test('normalizeClassificationStatus maps legacy PENDING_DESKTOP_RULES and excludesFromBalance safely', () => {
  assert.equal(normalizeClassificationStatus('PENDING_DESKTOP_RULES', false), 'PENDING_CLASSIFICATION');
  assert.equal(normalizeClassificationStatus('PENDING_CLASSIFICATION', false), 'PENDING_CLASSIFICATION');
  assert.equal(normalizeClassificationStatus('BUSINESS_PRODUCT', true), 'NON_BUSINESS');
  assert.equal(normalizeClassificationStatus('BUSINESS_SERVICE', false), 'BUSINESS_SERVICE');
  assert.equal(normalizeClassificationStatus('REMOVED', false), 'NON_BUSINESS');
});

test('canClassifyTransaction locks already classified or excluded records', () => {
  assert.equal(canClassifyTransaction(makeTx({ classificationStatus: 'PENDING_CLASSIFICATION' })), true);
  assert.equal(canClassifyTransaction(makeTx({ classificationStatus: 'BUSINESS_PRODUCT' })), false);
  assert.equal(canClassifyTransaction(makeTx({ classificationStatus: 'BUSINESS_SERVICE' })), false);
  assert.equal(
    canClassifyTransaction(makeTx({ classificationStatus: 'PENDING_CLASSIFICATION', excludesFromBalance: true })),
    false
  );
});

test('computeTransactionBusinessContribution excludes pending, non-business, and transfers while deducting fees/discounts', () => {
  assert.equal(computeTransactionBusinessContribution(makeTx({ amount: 5000 })), 0);
  assert.equal(
    computeTransactionBusinessContribution(
      makeTx({ amount: 2500, classificationStatus: 'NON_BUSINESS', excludesFromBalance: true })
    ),
    0
  );
  assert.equal(
    computeTransactionBusinessContribution(
      makeTx({ amount: 1500, direction: 'TRANSFER', classificationStatus: 'BUSINESS_PRODUCT' })
    ),
    0
  );
  assert.equal(
    computeTransactionBusinessContribution(
      makeTx({
        amount: 1500,
        saleTotalPrice: 1500,
        transactionCost: 23,
        classificationStatus: 'BUSINESS_PRODUCT',
      })
    ),
    1477
  );
  assert.equal(
    computeTransactionBusinessContribution(
      makeTx({
        amount: 500,
        serviceTotalRevenue: 450,
        transactionCost: 0,
        classificationStatus: 'BUSINESS_SERVICE',
      })
    ),
    450
  );
  assert.equal(
    computeTransactionBusinessContribution(
      makeTx({
        amount: 800,
        direction: 'OUTFLOW',
        transactionCost: 15,
        classificationStatus: 'BUSINESS_PRODUCT',
      })
    ),
    -815
  );
});

test('computeBusinessBalanceSummary deduplicates identical events and excludes non-business/pending amounts', () => {
  const summary = computeBusinessBalanceSummary([
    makeTx({ id: '1', amount: 2000, classificationStatus: 'PENDING_CLASSIFICATION' }),
    makeTx({ id: '2', amount: 3000, classificationStatus: 'NON_BUSINESS', excludesFromBalance: true }),
    makeTx({ id: '3', amount: 1200, saleTotalPrice: 1200, classificationStatus: 'BUSINESS_PRODUCT' }),
    makeTx({ id: '3', amount: 1200, saleTotalPrice: 1200, classificationStatus: 'BUSINESS_PRODUCT' }),
    makeTx({
      id: '4',
      amount: 600,
      serviceTotalRevenue: 500,
      transactionCost: 10,
      classificationStatus: 'BUSINESS_SERVICE',
    }),
  ]);

  assert.equal(summary.businessProductCount, 1);
  assert.equal(summary.businessProductNet, 1200);
  assert.equal(summary.businessServiceCount, 1);
  assert.equal(summary.businessServiceNet, 490);
  assert.equal(summary.businessBalance, 1690);
  assert.equal(summary.pendingCount, 1);
  assert.equal(summary.pendingExcludedAmount, 2000);
  assert.equal(summary.nonBusinessCount, 1);
  assert.equal(summary.nonBusinessExcludedAmount, 3000);
});

test('isSaleAvailableForLinking prevents associating an already-linked sale with a second transaction', () => {
  const unlinkedSale: RecentSaleOption = {
    saleId: 41,
    productId: 7,
    productName: 'A4 Paper',
    qtySold: 2,
    totalPrice: 1200,
    saleDate: '2026-11-23T08:00:00Z',
    alreadyLinkedEventId: null,
  };
  const linkedSale: RecentSaleOption = {
    ...unlinkedSale,
    saleId: 42,
    alreadyLinkedEventId: 'evt-other',
  };

  assert.equal(isSaleAvailableForLinking(unlinkedSale, 'evt-mine'), true);
  assert.equal(isSaleAvailableForLinking(linkedSale, 'evt-mine'), false);
  assert.equal(isSaleAvailableForLinking(linkedSale, 'evt-other'), true);
  assert.equal(formatKes(-450), '-Ksh 450');
});
