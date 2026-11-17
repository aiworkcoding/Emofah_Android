import { GoogleGenAI, ThinkingLevel, Type } from "npm:@google/genai@^2.4.0";

interface CatalogContextItem {
  id: number;
  name: string;
  category?: string;
  buy_price?: number | null;
  sell_price?: number | null;
}

interface ExtractReceiptRequestBody {
  image_base64?: string;
  mime_type?: string;
  ocr_text?: string;
  catalog_context?: CatalogContextItem[];
}

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: CORS_HEADERS });
  }

  if (req.method !== "POST") {
    return new Response(
      JSON.stringify({ error: "Method not allowed" }),
      { status: 405, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } },
    );
  }

  const apiKey = Deno.env.get("GEMINI_API_KEY")?.trim();
  if (!apiKey) {
    return new Response(
      JSON.stringify({ error: "Receipt extraction service is not configured on backend." }),
      { status: 503, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } },
    );
  }

  try {
    const body = (await req.json()) as ExtractReceiptRequestBody;
    const imageBase64 = (body.image_base64 ?? "").trim();
    const mimeType = (body.mime_type ?? "image/jpeg").trim();
    const ocrText = (body.ocr_text ?? "").trim();
    const catalogContext = Array.isArray(body.catalog_context)
      ? body.catalog_context.slice(0, 150)
      : [];

    if (!imageBase64 && !ocrText) {
      return new Response(
        JSON.stringify({ error: "Provide receipt image_base64 or ocr_text." }),
        { status: 400, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } },
      );
    }

    const validIds = new Set<number>(
      catalogContext
        .map((c) => Number(c.id))
        .filter((id) => Number.isInteger(id) && id > 0),
    );

    const compactCatalog = catalogContext
      .map((c) => {
        const parts = [
          `#${c.id}: ${c.name}`,
          c.category ? `[${c.category}]` : "",
        ].filter(Boolean);
        return parts.join(" ");
      })
      .join("\n");

    const ai = new GoogleGenAI({
      apiKey,
      httpOptions: {
        headers: {
          "User-Agent": "aistudio-build",
        },
      },
    });

    const systemInstruction = [
      "You extract structured line items from physical supplier/purchase receipts for Emofah Agencies (stationery, books, printing/cyber shop, and electronics).",
      "Read only what is visibly printed or handwritten on the receipt; never fabricate items, quantities, or prices.",
      "Classify each line item into RESALE_PRODUCT (inventory bought to resell), CONSUMABLE (printing paper, toner, ink, staples, cleaning supplies used internally), or EQUIPMENT (printer, laminator, computer, furniture).",
      "If an item clearly matches a product in the provided catalogue list, set matched_product_id to that exact numeric ID; otherwise omit or set 0. Never invent a product_id not in the catalogue list.",
      "If any number or text is blurry or ambiguous, add a concise warning in warnings.",
    ].join(" ");

    const promptParts = [
      ocrText ? `On-device OCR text:\n${ocrText}` : "On-device OCR text: (none)",
      compactCatalog ? `Existing product catalogue:\n${compactCatalog}` : "Existing product catalogue: (none provided)",
      "Extract supplier name, invoice/receipt reference, date, line items (name, qty, unit_cost, line_total, suggested_item_type, matched_product_id), and total amount.",
    ].join("\n\n");

    const parts: Array<Record<string, unknown>> = [];
    if (imageBase64) {
      parts.push({
        inlineData: {
          mimeType,
          data: imageBase64,
        },
      });
    }
    parts.push({ text: promptParts });

    const response = await ai.models.generateContent({
      model: "gemini-4-hydrogen",
      contents: { parts },
      config: {
        systemInstruction,
        thinkingConfig: { thinkingLevel: ThinkingLevel.LOW },
        responseMimeType: "application/json",
        responseSchema: {
          type: Type.OBJECT,
          properties: {
            supplier_name: {
              type: Type.STRING,
              description: "Supplier or shop name printed at top of receipt, or empty string if unreadable.",
            },
            invoice_ref: {
              type: Type.STRING,
              description: "Receipt, invoice, or tax serial number if visible, or empty string.",
            },
            receipt_date: {
              type: Type.STRING,
              description: "Date string visible on the receipt, or empty string.",
            },
            total_amount: {
              type: Type.NUMBER,
              description: "Grand total paid on receipt, or 0 if not legible.",
            },
            warnings: {
              type: Type.ARRAY,
              items: { type: Type.STRING },
              description: "Any blur, handwriting, tax discrepancy, or uncertainty notes for human review.",
            },
            items: {
              type: Type.ARRAY,
              items: {
                type: Type.OBJECT,
                properties: {
                  raw_name: {
                    type: Type.STRING,
                    description: "Item description read from the receipt line.",
                  },
                  qty: {
                    type: Type.INTEGER,
                    description: "Purchased quantity (integer >= 1).",
                  },
                  unit_cost: {
                    type: Type.NUMBER,
                    description: "Cost per unit in KES (or 0 if only line total is shown).",
                  },
                  line_total: {
                    type: Type.NUMBER,
                    description: "Line total in KES.",
                  },
                  suggested_item_type: {
                    type: Type.STRING,
                    description: "One of RESALE_PRODUCT, CONSUMABLE, EQUIPMENT.",
                  },
                  matched_product_id: {
                    type: Type.INTEGER,
                    description: "Exact numeric ID from provided catalogue list if matched, else 0.",
                  },
                  confidence_note: {
                    type: Type.STRING,
                    description: "Short note if quantity/price needed estimation.",
                  },
                },
              },
            },
          },
        },
      },
    });

    const rawText = (response.text ?? "{}").trim();
    const parsed = JSON.parse(rawText) as {
      supplier_name?: string;
      invoice_ref?: string;
      receipt_date?: string;
      total_amount?: number;
      warnings?: string[];
      items?: Array<{
        raw_name?: string;
        qty?: number;
        unit_cost?: number;
        line_total?: number;
        suggested_item_type?: string;
        matched_product_id?: number;
        confidence_note?: string;
      }>;
    };

    const allowedTypes = new Set(["RESALE_PRODUCT", "CONSUMABLE", "EQUIPMENT"]);
    const sanitizedItems = (Array.isArray(parsed.items) ? parsed.items : [])
      .map((item) => {
        const qty = Math.max(1, Math.round(Number(item.qty) || 1));
        const rawUnitCost = Number(item.unit_cost) || 0;
        const rawLineTotal = Number(item.line_total) || 0;
        const unitCost = rawUnitCost > 0 ? rawUnitCost : (rawLineTotal > 0 ? Number((rawLineTotal / qty).toFixed(2)) : 0);
        const lineTotal = rawLineTotal > 0 ? rawLineTotal : Number((unitCost * qty).toFixed(2));
        const rawType = typeof item.suggested_item_type === "string"
          ? item.suggested_item_type.trim().toUpperCase()
          : "RESALE_PRODUCT";
        const itemType = allowedTypes.has(rawType) ? rawType : "RESALE_PRODUCT";
        const candidateId = Number(item.matched_product_id);
        const matchedProductId = Number.isInteger(candidateId) && validIds.has(candidateId) ? candidateId : null;
        return {
          raw_name: typeof item.raw_name === "string" ? item.raw_name.trim() : "",
          qty,
          unit_cost: Math.max(0, unitCost),
          line_total: Math.max(0, lineTotal),
          suggested_item_type: itemType,
          matched_product_id: matchedProductId,
          confidence_note: typeof item.confidence_note === "string" ? item.confidence_note.trim() : null,
        };
      })
      .filter((item) => item.raw_name.length > 0);

    const warnings = (Array.isArray(parsed.warnings) ? parsed.warnings : [])
      .filter((w): w is string => typeof w === "string" && w.trim().length > 0)
      .map((w) => w.trim());

    return new Response(
      JSON.stringify({
        supplier_name: typeof parsed.supplier_name === "string" ? parsed.supplier_name.trim() : "",
        invoice_ref: typeof parsed.invoice_ref === "string" ? parsed.invoice_ref.trim() : "",
        receipt_date: typeof parsed.receipt_date === "string" ? parsed.receipt_date.trim() : "",
        total_amount: Math.max(0, Number(parsed.total_amount) || 0),
        warnings,
        items: sanitizedItems,
      }),
      { status: 200, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } },
    );
  } catch (err) {
    const message = err instanceof Error ? err.message : "Receipt extraction failed";
    return new Response(
      JSON.stringify({ error: message, items: [] }),
      { status: 500, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } },
    );
  }
});
