import {
  computeBusinessBalanceSummary,
  normalizeClassificationStatus,
  normalizeDirection,
  type BusinessBalanceSummary,
  type ClassificationHistoryEntry,
  type RecentSaleOption,
  type SharedFinancialTransaction,
} from './financialBalance';

const SUPABASE_URL = (
  (typeof import.meta !== 'undefined' && import.meta.env?.VITE_SUPABASE_URL) ||
  'https://nbmmfdiqywmkjaltezro.supabase.co'
).replace(/\/+$/, '');

const SUPABASE_ANON_KEY =
  (typeof import.meta !== 'undefined' && import.meta.env?.VITE_SUPABASE_ANON_KEY) ||
  'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im5ibW1mZGlxeXdta2phbHRlenJvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njc1NTcwNzQsImV4cCI6MjA4MzEzMzA3NH0.LvAJ0xmBohmOKW-7Va8poTsbqJACNMY87SAnZnzqqTs';

function supabaseHeaders(): Record<string, string> {
  return {
    apikey: SUPABASE_ANON_KEY,
    Authorization: `Bearer ${SUPABASE_ANON_KEY}`,
    'Content-Type': 'application/json',
    Accept: 'application/json',
  };
}

function toNumberOrNull(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const num = Number(value);
  return Number.isFinite(num) ? num : null;
}

export function mapRawEventRow(raw: Record<string, unknown>): SharedFinancialTransaction {
  const excludesFromBalance = Boolean(raw.excludes_from_balance);
  return {
    id: String(raw.id || ''),
    clientEventId: raw.client_event_id ? String(raw.client_event_id) : null,
    eventType: String(raw.event_type || 'MPESA_SMS_EVENT'),
    direction: normalizeDirection(raw.direction ? String(raw.direction) : 'INFLOW'),
    amount: Number(raw.amount) || 0,
    currency: String(raw.currency || 'KES'),
    classificationStatus: normalizeClassificationStatus(
      raw.classification_status ? String(raw.classification_status) : null,
      excludesFromBalance
    ),
    referenceCode: raw.reference_code ? String(raw.reference_code) : null,
    channel: raw.channel ? String(raw.channel) : null,
    counterparty: raw.counterparty ? String(raw.counterparty) : null,
    counterpartyAccount: raw.counterparty_account ? String(raw.counterparty_account) : null,
    transactionCost: toNumberOrNull(raw.transaction_cost),
    newBalance: toNumberOrNull(raw.new_balance),
    occurredText: raw.occurred_text ? String(raw.occurred_text) : null,
    rawEvidence: raw.raw_evidence ? String(raw.raw_evidence) : null,
    parseStatus: raw.parse_status ? String(raw.parse_status) : null,
    source: String(raw.source || 'ANDROID'),
    actorId: raw.actor_id ? String(raw.actor_id) : null,
    deviceId: raw.device_id ? String(raw.device_id) : null,
    notes: raw.notes ? String(raw.notes) : null,
    occurredAt: String(raw.occurred_at || raw.created_at || new Date().toISOString()),
    createdAt: String(raw.created_at || raw.occurred_at || new Date().toISOString()),
    updatedAt: raw.updated_at ? String(raw.updated_at) : null,
    linkedSaleId: toNumberOrNull(raw.linked_sale_id),
    linkedClientSaleId: raw.linked_client_sale_id ? String(raw.linked_client_sale_id) : null,
    linkedServiceId: raw.linked_service_id ? String(raw.linked_service_id) : null,
    linkedClientServiceId: raw.linked_client_service_id ? String(raw.linked_client_service_id) : null,
    linkedProductId: toNumberOrNull(raw.linked_product_id),
    linkedProductName: raw.linked_product_name ? String(raw.linked_product_name) : null,
    linkedQuantity: toNumberOrNull(raw.linked_quantity),
    classificationNote: raw.classification_note ? String(raw.classification_note) : null,
    classifiedAt: raw.classified_at ? String(raw.classified_at) : null,
    classifiedBy: raw.classified_by ? String(raw.classified_by) : null,
    classifiedDevice: raw.classified_device ? String(raw.classified_device) : null,
    excludesFromBalance,
    serviceUnitPrice: toNumberOrNull(raw.service_unit_price),
    serviceDiscountAmount: toNumberOrNull(raw.service_discount_amount),
    serviceTotalRevenue: toNumberOrNull(raw.service_total_revenue),
    serviceType: raw.service_type ? String(raw.service_type) : null,
    serviceLabel: raw.service_label ? String(raw.service_label) : null,
    saleTotalPrice: toNumberOrNull(raw.sale_total_price),
    saleQtySold: toNumberOrNull(raw.sale_qty_sold),
  };
}

export interface SharedTransactionsSnapshot {
  transactions: SharedFinancialTransaction[];
  recentSales: RecentSaleOption[];
  history: ClassificationHistoryEntry[];
  summary: BusinessBalanceSummary;
  rpcDeployed: boolean;
  warning?: string;
}

export interface ClassificationActionResult {
  status:
    | 'COMMITTED'
    | 'DUPLICATE_IGNORED'
    | 'ALREADY_CLASSIFIED'
    | 'REJECTED'
    | 'ERROR';
  message: string;
  event?: SharedFinancialTransaction;
}

export async function fetchSharedTransactions(
  limit = 150
): Promise<SharedTransactionsSnapshot> {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/get_financial_transactions`, {
    method: 'POST',
    headers: supabaseHeaders(),
    body: JSON.stringify({ p_limit: limit, p_status_filter: null }),
  });

  if (res.ok) {
    const data = await res.json();
    const rawTxs: Record<string, unknown>[] = Array.isArray(data.transactions)
      ? data.transactions
      : [];
    const transactions = rawTxs.map(mapRawEventRow);

    const rawSales: Record<string, unknown>[] = Array.isArray(data.recent_sales)
      ? data.recent_sales
      : [];
    const recentSales: RecentSaleOption[] = rawSales.map((s) => ({
      saleId: Number(s.sale_id) || 0,
      productId: Number(s.product_id) || 0,
      productName: String(s.product_name || `Product #${s.product_id}`),
      qtySold: Number(s.qty_sold) || 0,
      totalPrice: Number(s.total_price) || 0,
      saleDate: String(s.sale_date || ''),
      alreadyLinkedEventId: s.already_linked_event_id ? String(s.already_linked_event_id) : null,
    }));

    const rawHistory: Record<string, unknown>[] = Array.isArray(data.history)
      ? data.history
      : [];
    const history: ClassificationHistoryEntry[] = rawHistory.map((h) => ({
      id: String(h.id || ''),
      eventId: String(h.event_id || ''),
      previousStatus: h.previous_status ? String(h.previous_status) : null,
      newStatus: String(h.new_status || ''),
      action: String(h.action || ''),
      linkedProductId: toNumberOrNull(h.linked_product_id),
      linkedSaleId: toNumberOrNull(h.linked_sale_id),
      linkedServiceId: h.linked_service_id ? String(h.linked_service_id) : null,
      note: h.note ? String(h.note) : null,
      actorId: h.actor_id ? String(h.actor_id) : null,
      deviceId: h.device_id ? String(h.device_id) : null,
      metadata: (h.metadata && typeof h.metadata === 'object' ? h.metadata : {}) as Record<
        string,
        unknown
      >,
      createdAt: String(h.created_at || ''),
    }));

    return {
      transactions,
      recentSales,
      history,
      summary: computeBusinessBalanceSummary(transactions),
      rpcDeployed: true,
    };
  }

  const errText = await res.text();
  if (res.status === 404 || errText.includes('PGRST202')) {
    return fallbackFetchRest(errText);
  }
  throw new Error(errText || `Supabase HTTP ${res.status}`);
}

async function fallbackFetchRest(reason: string): Promise<SharedTransactionsSnapshot> {
  const eventsRes = await fetch(
    `${SUPABASE_URL}/rest/v1/android_financial_events?select=*&order=occurred_at.desc&limit=120`,
    { headers: supabaseHeaders() }
  );
  if (!eventsRes.ok) {
    throw new Error(
      `Deploy the shared transaction classification SQL migration in Backend Setup (${reason.slice(0, 120)}).`
    );
  }
  const rows: Record<string, unknown>[] = await eventsRes.json();
  const transactions = rows.map(mapRawEventRow);
  return {
    transactions,
    recentSales: [],
    history: [],
    summary: computeBusinessBalanceSummary(transactions),
    rpcDeployed: false,
    warning:
      'Shared classification RPCs are not deployed yet. Apply migration 20261122_shared_transaction_classification.sql in Backend Setup.',
  };
}

function makeClassificationIdempotencyKey(eventId: string, action: string): string {
  const randomSuffix =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  return `cls-${action}-${eventId}-${randomSuffix}`;
}

async function parseClassificationRpcResponse(
  res: Response,
  fallbackActionLabel: string
): Promise<ClassificationActionResult> {
  const text = await res.text();
  if (!res.ok) {
    if (res.status === 404 || text.includes('PGRST202')) {
      return {
        status: 'ERROR',
        message:
          'Shared classification RPC is not deployed in Supabase yet. Run the SQL from Backend Setup first.',
      };
    }
    return {
      status: 'ERROR',
      message: text || `Request failed (HTTP ${res.status}).`,
    };
  }

  let data: Record<string, unknown>;
  try {
    data = JSON.parse(text);
  } catch {
    return { status: 'ERROR', message: 'Invalid JSON response from Supabase.' };
  }

  const rpcStatus = String(data.status || '');
  const parsedEvent =
    data.event && typeof data.event === 'object'
      ? mapRawEventRow(data.event as Record<string, unknown>)
      : undefined;

  if (rpcStatus === 'COMMITTED') {
    return {
      status: 'COMMITTED',
      message: fallbackActionLabel,
      event: parsedEvent,
    };
  }
  if (rpcStatus === 'DUPLICATE_IGNORED') {
    return {
      status: 'DUPLICATE_IGNORED',
      message: 'This classification was already recorded.',
      event: parsedEvent,
    };
  }
  if (rpcStatus === 'ALREADY_CLASSIFIED') {
    return {
      status: 'ALREADY_CLASSIFIED',
      message: String(
        data.message ||
          `Transaction was already classified (${String(data.current_status || 'updated')}). Syncing latest state.`
      ),
      event: parsedEvent,
    };
  }
  return {
    status: 'REJECTED',
    message: String(data.message || `Classification rejected (${rpcStatus || 'ERROR'}).`),
    event: parsedEvent,
  };
}

export async function classifyTransactionAsProduct(params: {
  eventId: string;
  productId?: number | null;
  quantity?: number;
  existingSaleId?: number | null;
  unitSellPrice?: number | null;
  paymentMethod?: 'CASH' | 'MPESA' | 'BANK';
  note?: string | null;
  actorId?: string;
  deviceId?: string;
  idempotencyKey?: string;
}): Promise<ClassificationActionResult> {
  const idemKey =
    params.idempotencyKey || makeClassificationIdempotencyKey(params.eventId, 'product');

  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/rpc/classify_financial_transaction_as_product`,
    {
      method: 'POST',
      headers: supabaseHeaders(),
      body: JSON.stringify({
        p_event_id: params.eventId,
        p_classification_idempotency_key: idemKey,
        p_product_id: params.productId ?? null,
        p_quantity: params.quantity ?? 1,
        p_existing_sale_id: params.existingSaleId ?? null,
        p_unit_sell_price: params.unitSellPrice ?? null,
        p_payment_method: params.paymentMethod ?? 'MPESA',
        p_note: params.note?.trim() || null,
        p_actor_id: params.actorId || 'desktop_operator',
        p_device_id: params.deviceId || 'desktop-web',
      }),
    }
  );

  return parseClassificationRpcResponse(
    res,
    params.existingSaleId
      ? `Linked transaction to existing sale #${params.existingSaleId}.`
      : 'Created product sale and updated inventory.'
  );
}

export async function classifyTransactionAsService(params: {
  eventId: string;
  serviceType: string;
  serviceLabel?: string | null;
  quantityOrDuration: number;
  unitLabel?: string;
  unitPrice?: number | null;
  discountAmount?: number;
  note?: string | null;
  actorId?: string;
  deviceId?: string;
  idempotencyKey?: string;
}): Promise<ClassificationActionResult> {
  const idemKey =
    params.idempotencyKey || makeClassificationIdempotencyKey(params.eventId, 'service');

  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/rpc/classify_financial_transaction_as_service`,
    {
      method: 'POST',
      headers: supabaseHeaders(),
      body: JSON.stringify({
        p_event_id: params.eventId,
        p_classification_idempotency_key: idemKey,
        p_service_type: params.serviceType,
        p_service_label: params.serviceLabel?.trim() || null,
        p_quantity_or_duration: params.quantityOrDuration,
        p_unit_label: params.unitLabel?.trim() || 'units',
        p_unit_price: params.unitPrice ?? null,
        p_discount_amount: params.discountAmount ?? 0,
        p_consumables: [],
        p_note: params.note?.trim() || null,
        p_actor_id: params.actorId || 'desktop_operator',
        p_device_id: params.deviceId || 'desktop-web',
      }),
    }
  );

  return parseClassificationRpcResponse(
    res,
    `Linked transaction to service (${params.serviceLabel || params.serviceType}).`
  );
}

export async function classifyTransactionAsNonBusiness(params: {
  eventId: string;
  reason?: string | null;
  actorId?: string;
  deviceId?: string;
  idempotencyKey?: string;
}): Promise<ClassificationActionResult> {
  const idemKey =
    params.idempotencyKey || makeClassificationIdempotencyKey(params.eventId, 'nonbiz');

  const res = await fetch(
    `${SUPABASE_URL}/rest/v1/rpc/classify_financial_transaction_as_non_business`,
    {
      method: 'POST',
      headers: supabaseHeaders(),
      body: JSON.stringify({
        p_event_id: params.eventId,
        p_classification_idempotency_key: idemKey,
        p_reason: params.reason?.trim() || 'Personal / non-business transaction',
        p_actor_id: params.actorId || 'desktop_operator',
        p_device_id: params.deviceId || 'desktop-web',
      }),
    }
  );

  return parseClassificationRpcResponse(
    res,
    'Excluded from business balance while preserving original transaction & audit log.'
  );
}

export interface RealtimeSubscriptionHandle {
  close: () => void;
}

/**
 * Connects directly to Supabase Realtime Phoenix WebSocket channel for live
 * `postgres_changes` events on `public.android_financial_events`, with heartbeat
 * and bounded reconnect backoff.
 */
export function subscribeToTransactionUpdates(options: {
  onChange: () => void;
  onConnectionChange?: (connected: boolean) => void;
}): RealtimeSubscriptionHandle {
  if (typeof WebSocket === 'undefined') {
    return { close: () => undefined };
  }

  let closed = false;
  let socket: WebSocket | null = null;
  let heartbeatTimer: number | null = null;
  let reconnectTimer: number | null = null;
  let refCounter = 1;
  let reconnectAttempts = 0;

  const clearTimers = () => {
    if (heartbeatTimer !== null) {
      window.clearInterval(heartbeatTimer);
      heartbeatTimer = null;
    }
    if (reconnectTimer !== null) {
      window.clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  };

  const connect = () => {
    if (closed) return;
    clearTimers();

    const wsBase = SUPABASE_URL.replace(/^https:\/\//i, 'wss://').replace(/^http:\/\//i, 'ws://');
    const wsUrl = `${wsBase}/realtime/v1/websocket?apikey=${encodeURIComponent(
      SUPABASE_ANON_KEY
    )}&vsn=1.0.0`;

    try {
      socket = new WebSocket(wsUrl);
    } catch {
      options.onConnectionChange?.(false);
      scheduleReconnect();
      return;
    }

    socket.onopen = () => {
      if (closed || !socket) return;
      reconnectAttempts = 0;
      const joinRef = String(refCounter++);
      socket.send(
        JSON.stringify({
          topic: 'realtime:public:android_financial_events',
          event: 'phx_join',
          payload: {
            config: {
              broadcast: { ack: false, self: false },
              presence: { key: '' },
              postgres_changes: [
                {
                  event: '*',
                  schema: 'public',
                  table: 'android_financial_events',
                },
              ],
            },
            access_token: SUPABASE_ANON_KEY,
          },
          ref: joinRef,
        })
      );

      heartbeatTimer = window.setInterval(() => {
        if (socket && socket.readyState === WebSocket.OPEN) {
          socket.send(
            JSON.stringify({
              topic: 'phoenix',
              event: 'heartbeat',
              payload: {},
              ref: String(refCounter++),
            })
          );
        }
      }, 25_000);
    };

    socket.onmessage = (event) => {
      if (closed) return;
      try {
        const msg = JSON.parse(String(event.data || '{}'));
        if (msg.event === 'phx_reply' && msg.payload?.status === 'ok') {
          options.onConnectionChange?.(true);
          return;
        }
        if (msg.event === 'postgres_changes' || msg.event === 'INSERT' || msg.event === 'UPDATE') {
          options.onChange();
        }
      } catch {
        // Ignore malformed websocket frames
      }
    };

    socket.onerror = () => {
      options.onConnectionChange?.(false);
    };

    socket.onclose = () => {
      options.onConnectionChange?.(false);
      clearTimers();
      if (!closed) {
        scheduleReconnect();
      }
    };
  };

  const scheduleReconnect = () => {
    if (closed) return;
    const delayMs = Math.min(30_000, 1500 * Math.pow(2, Math.min(reconnectAttempts, 4)));
    reconnectAttempts += 1;
    reconnectTimer = window.setTimeout(() => {
      connect();
    }, delayMs);
  };

  connect();

  return {
    close: () => {
      closed = true;
      clearTimers();
      if (socket && socket.readyState <= WebSocket.OPEN) {
        try {
          socket.close();
        } catch {
          // Ignore close errors
        }
      }
      socket = null;
    },
  };
}

/**
 * Automatically captures an incoming or outgoing M-Pesa transaction into Supabase
 * marked as PENDING_CLASSIFICATION before any user prompt.
 */
export async function captureFinancialTransaction(params: {
  amount: number;
  direction?: 'INFLOW' | 'OUTFLOW' | 'TRANSFER';
  referenceCode?: string | null;
  channel?: string | null;
  counterparty?: string | null;
  counterpartyAccount?: string | null;
  transactionCost?: number | null;
  newBalance?: number | null;
  rawEvidence?: string | null;
  source?: string;
  actorId?: string;
  deviceId?: string;
  notes?: string | null;
}): Promise<SharedFinancialTransaction> {
  const clientEventId =
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : `ev-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;

  const direction = params.direction || 'INFLOW';
  const occurredAt = new Date().toISOString();
  const refCode =
    params.referenceCode ||
    `QG${Math.random().toString(36).substring(2, 6).toUpperCase()}${Math.floor(
      1000 + Math.random() * 9000
    )}`;

  try {
    const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/record_android_financial_event`, {
      method: 'POST',
      headers: supabaseHeaders(),
      body: JSON.stringify({
        p_client_event_id: clientEventId,
        p_idempotency_key: `cap-${clientEventId}`,
        p_event_type: 'MPESA_SMS_EVENT',
        p_direction: direction,
        p_amount: params.amount,
        p_currency: 'KES',
        p_reference_code: refCode,
        p_channel: params.channel || (direction === 'INFLOW' ? 'Received Money' : 'Paybill'),
        p_counterparty: params.counterparty || (direction === 'INFLOW' ? 'Customer' : 'Vendor'),
        p_counterparty_account: params.counterpartyAccount ?? null,
        p_transaction_cost: params.transactionCost ?? null,
        p_new_balance: params.newBalance ?? null,
        p_occurred_text: new Date().toLocaleTimeString(),
        p_raw_evidence: params.rawEvidence || `${refCode} Confirmed. Ksh${params.amount} received.`,
        p_parse_status: 'PARSED',
        p_source: params.source || 'ANDROID_CAPTURE',
        p_actor_id: params.actorId || 'counter_agent',
        p_device_id: params.deviceId || 'android-pos-1',
        p_notes: params.notes || 'Automatically recorded awaiting classification',
        p_occurred_at: occurredAt,
      }),
    });

    if (res.ok) {
      const data = await res.json();
      const serverEventId = String(data.server_event_id || clientEventId);
      return {
        id: serverEventId,
        clientEventId,
        eventType: 'MPESA_SMS_EVENT',
        direction,
        amount: params.amount,
        currency: 'KES',
        classificationStatus: 'PENDING_CLASSIFICATION',
        referenceCode: refCode,
        channel: params.channel || (direction === 'INFLOW' ? 'Received Money' : 'Paybill'),
        counterparty: params.counterparty || (direction === 'INFLOW' ? 'Customer' : 'Vendor'),
        counterpartyAccount: params.counterpartyAccount ?? null,
        transactionCost: params.transactionCost ?? null,
        newBalance: params.newBalance ?? null,
        occurredText: new Date().toLocaleTimeString(),
        rawEvidence: params.rawEvidence || `${refCode} Confirmed. Ksh${params.amount} received.`,
        parseStatus: 'PARSED',
        source: params.source || 'ANDROID_CAPTURE',
        actorId: params.actorId || 'counter_agent',
        deviceId: params.deviceId || 'android-pos-1',
        notes: params.notes || 'Automatically recorded awaiting classification',
        occurredAt,
        createdAt: occurredAt,
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
      };
    }
  } catch {
    // Fall through to client fallback return
  }

  // Fallback return if RPC offline or table unmigrated
  return {
    id: clientEventId,
    clientEventId,
    eventType: 'MPESA_SMS_EVENT',
    direction,
    amount: params.amount,
    currency: 'KES',
    classificationStatus: 'PENDING_CLASSIFICATION',
    referenceCode: refCode,
    channel: params.channel || (direction === 'INFLOW' ? 'Received Money' : 'Paybill'),
    counterparty: params.counterparty || (direction === 'INFLOW' ? 'Customer' : 'Vendor'),
    counterpartyAccount: params.counterpartyAccount ?? null,
    transactionCost: params.transactionCost ?? null,
    newBalance: params.newBalance ?? null,
    occurredText: new Date().toLocaleTimeString(),
    rawEvidence: params.rawEvidence || `${refCode} Confirmed. Ksh${params.amount} received.`,
    parseStatus: 'PARSED',
    source: params.source || 'ANDROID_CAPTURE',
    actorId: params.actorId || 'counter_agent',
    deviceId: params.deviceId || 'android-pos-1',
    notes: params.notes || 'Automatically recorded awaiting classification',
    occurredAt,
    createdAt: occurredAt,
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
  };
}

