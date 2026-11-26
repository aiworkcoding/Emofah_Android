/**
 * Emofah Ecosystem — Authoritative Supabase API Configuration & Provider
 * Cross-Platform Integration for Emofah Business Desktop
 *
 * Implements modern Supabase Publishable Key (sb_publishable_...) as primary client credential,
 * with optional legacy JWT (eyJ...) compatibility fallback.
 * Strictly prevents privileged keys (service_role / sb_secret_) from being configured.
 */

export const CANONICAL_SUPABASE_PROJECT_ID = 'nbmmfdiqywmkjaltezro';
export const CANONICAL_SUPABASE_URL = 'https://nbmmfdiqywmkjaltezro.supabase.co';
export const PRIMARY_PUBLISHABLE_KEY = 'sb_publishable_IYNhHXjZ1lPPdRFIpKuonA_ydMHV4t3';
export const DEFAULT_PUBLIC_STORAGE_BASE_URL = `${CANONICAL_SUPABASE_URL}/storage/v1/object/public/product-images/`;

export type SupabaseKeyType = 'PUBLISHABLE' | 'LEGACY_JWT' | 'INVALID';

export interface KeyValidationResult {
  valid: boolean;
  type: SupabaseKeyType;
  error?: string;
  isPrivilegedSecret?: boolean;
}

/**
 * Validates a candidate Supabase client credential.
 * Enforces security invariants:
 * 1. Privileged secrets (service_role, sb_secret_) are strictly rejected.
 * 2. Publishable keys (sb_publishable_...) are recognized as modern primary keys.
 * 3. Legacy anon JWTs (eyJ...) are supported as optional compatibility.
 */
export function validateClientKey(key: string | null | undefined): KeyValidationResult {
  const trimmed = (key || '').trim();
  if (!trimmed) {
    return { valid: false, type: 'INVALID', error: 'API key is missing or empty.' };
  }

  // Security Invariant: Detect and reject secret / service_role keys
  if (
    trimmed.startsWith('sb_secret_') ||
    trimmed.toLowerCase().includes('service_role')
  ) {
    return {
      valid: false,
      type: 'INVALID',
      isPrivilegedSecret: true,
      error: 'CRITICAL SECURITY VIOLATION: Privileged secret/service_role key detected. Secret keys must NEVER be configured in client applications.',
    };
  }

  // Check if it's a JWT (base64 header.payload.signature)
  if (trimmed.startsWith('eyJ')) {
    const parts = trimmed.split('.');
    if (parts.length === 3) {
      // Decode payload to verify it is NOT a service_role token
      try {
        const payloadJson = typeof atob === 'function'
          ? atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'))
          : Buffer.from(parts[1], 'base64').toString('utf8');
        if (payloadJson.includes('"role":"service_role"')) {
          return {
            valid: false,
            type: 'INVALID',
            isPrivilegedSecret: true,
            error: 'CRITICAL SECURITY VIOLATION: Privileged service_role JWT detected. Never expose service_role tokens in client apps.',
          };
        }
      } catch {
        // If decoding fails, proceed with format check
      }
      return { valid: true, type: 'LEGACY_JWT' };
    }
  }

  // Modern Publishable Key check (sb_publishable_...)
  if (trimmed.startsWith('sb_publishable_') && trimmed.length >= 25) {
    return { valid: true, type: 'PUBLISHABLE' };
  }

  return {
    valid: false,
    type: 'INVALID',
    error: `Unsupported API key format: "${redactKey(trimmed)}". Expected modern publishable key (sb_publishable_...) or legacy JWT (eyJ...).`,
  };
}

/**
 * Safely redacts an API key for diagnostic reporting, logging, and UI display.
 * Never prints full credentials.
 */
export function redactKey(key: string | null | undefined): string {
  const trimmed = (key || '').trim();
  if (!trimmed) return '(none)';
  if (trimmed.length <= 10) return `${trimmed.slice(0, 3)}***`;
  if (trimmed.startsWith('sb_publishable_')) {
    const prefix = 'sb_publishable_';
    const suffix = trimmed.slice(-4);
    return `${prefix}...${suffix}`;
  }
  if (trimmed.startsWith('eyJ')) {
    return `eyJ...${trimmed.slice(-4)}`;
  }
  return `${trimmed.slice(0, 4)}...${trimmed.slice(-4)}`;
}

export interface SupabaseConfigState {
  url: string;
  projectId: string;
  apiKey: string;
  keyType: SupabaseKeyType;
  redactedKey: string;
  isConfigured: boolean;
  publicStorageBaseUrl: string;
  configuredPublishableKey: string | null;
  configuredLegacyJwtKey: string | null;
  error?: string;
}

class SupabaseConfigManager {
  private state: SupabaseConfigState;

  constructor() {
    this.state = this.resolveConfiguration();
  }

  public resolveConfiguration(): SupabaseConfigState {
    const env = typeof import.meta !== 'undefined' && import.meta.env ? import.meta.env : {} as Record<string, string>;

    const configuredUrl = (env.VITE_SUPABASE_URL || CANONICAL_SUPABASE_URL).trim().replace(/\/+$/, '');
    const publicStorageBaseUrl = (env.VITE_SUPABASE_PUBLIC_STORAGE_BASE_URL || DEFAULT_PUBLIC_STORAGE_BASE_URL).trim();

    // Check environment variables for candidate keys
    const rawPublishableEnv = env.VITE_SUPABASE_PUBLISHABLE_KEY?.trim() || null;
    const rawLegacyEnv = env.VITE_SUPABASE_LEGACY_JWT?.trim() || null;
    const rawAnonEnv = env.VITE_SUPABASE_ANON_KEY?.trim() || null;

    let candidatePublishable: string | null = null;
    let candidateLegacy: string | null = null;

    // Categorize rawAnonEnv if provided
    if (rawAnonEnv) {
      if (rawAnonEnv.startsWith('sb_publishable_')) {
        candidatePublishable = rawAnonEnv;
      } else if (rawAnonEnv.startsWith('eyJ')) {
        candidateLegacy = rawAnonEnv;
      }
    }

    if (rawPublishableEnv) {
      candidatePublishable = rawPublishableEnv;
    }
    if (rawLegacyEnv) {
      candidateLegacy = rawLegacyEnv;
    }

    // Default primary fallback is the supplied authoritative publishable key
    if (!candidatePublishable) {
      candidatePublishable = PRIMARY_PUBLISHABLE_KEY;
    }

    // Credential precedence:
    // 1. Explicitly configured & valid publishable key (Primary)
    // 2. Explicitly configured & valid legacy JWT anon key (Optional Compatibility)
    // 3. Error
    let selectedKey = '';
    let selectedType: SupabaseKeyType = 'INVALID';
    let configError: string | undefined;

    if (candidatePublishable) {
      const pubValidation = validateClientKey(candidatePublishable);
      if (pubValidation.valid) {
        selectedKey = candidatePublishable;
        selectedType = 'PUBLISHABLE';
      } else if (pubValidation.isPrivilegedSecret) {
        configError = pubValidation.error;
      }
    }

    if (!selectedKey && candidateLegacy) {
      const legacyValidation = validateClientKey(candidateLegacy);
      if (legacyValidation.valid) {
        selectedKey = candidateLegacy;
        selectedType = 'LEGACY_JWT';
      } else if (legacyValidation.isPrivilegedSecret) {
        configError = legacyValidation.error;
      }
    }

    if (!selectedKey && !configError) {
      configError = 'No valid Supabase public credential configured.';
    }

    const isConfigured = Boolean(
      configuredUrl.startsWith('https://') &&
      selectedKey &&
      selectedType !== 'INVALID' &&
      !configError
    );

    return {
      url: configuredUrl,
      projectId: CANONICAL_SUPABASE_PROJECT_ID,
      apiKey: selectedKey,
      keyType: selectedType,
      redactedKey: redactKey(selectedKey),
      isConfigured,
      publicStorageBaseUrl,
      configuredPublishableKey: candidatePublishable,
      configuredLegacyJwtKey: candidateLegacy,
      error: configError,
    };
  }

  public getState(): SupabaseConfigState {
    return this.state;
  }

  public getUrl(): string {
    return this.state.url;
  }

  public getProjectId(): string {
    return this.state.projectId;
  }

  public getApiKey(): string {
    if (!this.state.isConfigured && this.state.error) {
      throw new Error(`[SupabaseConfig Error] ${this.state.error}`);
    }
    return this.state.apiKey;
  }

  public getKeyType(): SupabaseKeyType {
    return this.state.keyType;
  }

  public getRedactedKey(): string {
    return this.state.redactedKey;
  }

  public isConfigured(): boolean {
    return this.state.isConfigured;
  }

  public getHeaders(customBearerToken?: string | null): Record<string, string> {
    const key = this.getApiKey();
    const token = customBearerToken?.trim() || key;
    return {
      apikey: key,
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
      Accept: 'application/json',
    };
  }

  public getRealtimeWebSocketUrl(): string {
    const key = this.getApiKey();
    const wsBase = this.state.url
      .replace(/^https:\/\//i, 'wss://')
      .replace(/^http:\/\//i, 'ws://');
    return `${wsBase}/realtime/v1/websocket?apikey=${encodeURIComponent(key)}&vsn=1.0.0`;
  }

  public normalizeProductImageUrl(raw: string | null | undefined): string | null {
    const trimmed = raw?.trim();
    if (!trimmed || trimmed.toLowerCase() === 'null') return null;
    if (/^https?:\/\//i.test(trimmed)) return trimmed;
    const base = this.state.publicStorageBaseUrl.replace(/\/+$/, '');
    const cleanPath = trimmed.replace(/^\/+/, '').replace(/^product-images\//, '');
    return `${base}/${cleanPath}`;
  }
}

export const supabaseConfig = new SupabaseConfigManager();
