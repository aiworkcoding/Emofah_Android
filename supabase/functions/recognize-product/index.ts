import { GoogleGenAI, ThinkingLevel, Type } from "npm:@google/genai@^2.4.0";

interface CatalogContextItem {
  id: number;
  name: string;
  category?: string;
  variant_label?: string | null;
  brand_hint?: string | null;
}

interface RecognizeRequestBody {
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
      JSON.stringify({ error: "Recognition service is not configured on backend." }),
      { status: 503, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } },
    );
  }

  try {
    const body = (await req.json()) as RecognizeRequestBody;
    const imageBase64 = (body.image_base64 ?? "").trim();
    const mimeType = (body.mime_type ?? "image/jpeg").trim();
    const ocrText = (body.ocr_text ?? "").trim();
    const catalogContext = Array.isArray(body.catalog_context)
      ? body.catalog_context.slice(0, 150)
      : [];

    if (!imageBase64) {
      return new Response(
        JSON.stringify({ error: "Missing image_base64 payload." }),
        { status: 400, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } },
      );
    }

    if (catalogContext.length === 0) {
      return new Response(
        JSON.stringify({ candidates: [], extracted_label: null }),
        { status: 200, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } },
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
          c.variant_label ? `(${c.variant_label})` : "",
          c.brand_hint ? `brand=${c.brand_hint}` : "",
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
      "You identify retail shop products for Emofah Agencies strictly from the provided catalogue list.",
      "Never invent a product_id that is not present in the catalogue list.",
      "Do not guess prices or stock quantities.",
      "Return up to 3 best matching product_ids in descending confidence, or an empty candidates array if no catalogue item matches reliably.",
      "Pay close attention to variant distinctions such as size, colour, pack count, or page count.",
    ].join(" ");

    const promptText = [
      ocrText ? `On-device OCR text: "${ocrText}"` : "On-device OCR text: (none)",
      "Catalogue items:",
      compactCatalog,
      "Identify which catalogue product_ids match the photographed product.",
    ].join("\n\n");

    const response = await ai.models.generateContent({
      model: "gemini-4-hydrogen",
      contents: {
        parts: [
          {
            inlineData: {
              mimeType,
              data: imageBase64,
            },
          },
          { text: promptText },
        ],
      },
      config: {
        systemInstruction,
        thinkingConfig: { thinkingLevel: ThinkingLevel.LOW },
        responseMimeType: "application/json",
        responseSchema: {
          type: Type.OBJECT,
          properties: {
            extracted_label: {
              type: Type.STRING,
              description: "Short visible product/brand/size label read from packaging, if any.",
            },
            candidates: {
              type: Type.ARRAY,
              items: {
                type: Type.OBJECT,
                properties: {
                  product_id: {
                    type: Type.INTEGER,
                    description: "Exact numeric ID from the provided catalogue list.",
                  },
                  reason: {
                    type: Type.STRING,
                    description: "Brief plain-language reason for this candidate match.",
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
      extracted_label?: string | null;
      candidates?: Array<{ product_id?: unknown; reason?: string }>;
    };

    const filteredCandidates = (Array.isArray(parsed.candidates) ? parsed.candidates : [])
      .map((c) => ({
        product_id: Number(c.product_id),
        reason: typeof c.reason === "string" && c.reason.trim() ? c.reason.trim() : "Cloud visual match",
      }))
      .filter((c) => Number.isInteger(c.product_id) && validIds.has(c.product_id))
      .slice(0, 3);

    return new Response(
      JSON.stringify({
        extracted_label: typeof parsed.extracted_label === "string" ? parsed.extracted_label.trim() : null,
        candidates: filteredCandidates,
      }),
      { status: 200, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } },
    );
  } catch (err) {
    const message = err instanceof Error ? err.message : "Cloud recognition failed";
    return new Response(
      JSON.stringify({ error: message, candidates: [] }),
      { status: 500, headers: { ...CORS_HEADERS, "Content-Type": "application/json" } },
    );
  }
});
