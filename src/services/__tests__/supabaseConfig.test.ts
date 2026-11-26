import assert from 'node:assert/strict';
import test from 'node:test';
import {
  CANONICAL_SUPABASE_PROJECT_ID,
  CANONICAL_SUPABASE_URL,
  PRIMARY_PUBLISHABLE_KEY,
  redactKey,
  supabaseConfig,
  validateClientKey,
} from '../supabaseConfig.ts';

test('validateClientKey recognizes modern Supabase Publishable Key', () => {
  const result = validateClientKey(PRIMARY_PUBLISHABLE_KEY);
  assert.equal(result.valid, true);
  assert.equal(result.type, 'PUBLISHABLE');
  assert.equal(result.isPrivilegedSecret, undefined);
});

test('validateClientKey recognizes valid legacy JWT anon key format', () => {
  const legacyJwt = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im5ibW1mZGlxeXdta2phbHRlenJvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njc1NTcwNzQsImV4cCI6MjA4MzEzMzA3NH0.LvAJ0xmBohmOKW-7Va8poTsbqJACNMY87SAnZnzqqTs';
  const result = validateClientKey(legacyJwt);
  assert.equal(result.valid, true);
  assert.equal(result.type, 'LEGACY_JWT');
});

test('validateClientKey strictly rejects privileged secret / service_role keys', () => {
  // sb_secret_ format
  const secretKey = 'sb_secret_abcdef1234567890abcdef1234567890';
  const secretResult = validateClientKey(secretKey);
  assert.equal(secretResult.valid, false);
  assert.equal(secretResult.type, 'INVALID');
  assert.equal(secretResult.isPrivilegedSecret, true);
  assert.ok(secretResult.error?.includes('CRITICAL SECURITY VIOLATION'));

  // service_role JWT
  const serviceRoleJwt = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im5ibW1mZGlxeXdta2phbHRlenJvIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc2NzU1NzA3NCwiZXhwIjoyMDgzMTMzMDc0fQ.m2WpfShaqd4w-n3yRZZo0JCkM4p38exDthnMgb-618Q';
  const serviceRoleResult = validateClientKey(serviceRoleJwt);
  assert.equal(serviceRoleResult.valid, false);
  assert.equal(serviceRoleResult.type, 'INVALID');
  assert.equal(serviceRoleResult.isPrivilegedSecret, true);
  assert.ok(serviceRoleResult.error?.includes('CRITICAL SECURITY VIOLATION'));
});

test('validateClientKey rejects empty or malformed keys', () => {
  assert.equal(validateClientKey('').valid, false);
  assert.equal(validateClientKey(null).valid, false);
  assert.equal(validateClientKey('short_key').valid, false);
});

test('redactKey redacts credentials safely without exposing full secrets', () => {
  assert.equal(redactKey(PRIMARY_PUBLISHABLE_KEY), 'sb_publishable_...V4t3');
  assert.equal(redactKey('eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.abc.xyz123'), 'eyJ...z123');
  assert.equal(redactKey(''), '(none)');
  assert.equal(redactKey(null), '(none)');
});

test('supabaseConfig initializes with canonical project defaults', () => {
  assert.equal(supabaseConfig.getUrl(), CANONICAL_SUPABASE_URL);
  assert.equal(supabaseConfig.getProjectId(), CANONICAL_SUPABASE_PROJECT_ID);
  assert.equal(supabaseConfig.getKeyType(), 'PUBLISHABLE');
  assert.equal(supabaseConfig.getApiKey(), PRIMARY_PUBLISHABLE_KEY);
  assert.equal(supabaseConfig.isConfigured(), true);
  assert.equal(supabaseConfig.getRedactedKey(), 'sb_publishable_...V4t3');
});

test('supabaseConfig generates correct headers with apikey and Authorization', () => {
  const headers = supabaseConfig.getHeaders();
  assert.equal(headers.apikey, PRIMARY_PUBLISHABLE_KEY);
  assert.equal(headers.Authorization, `Bearer ${PRIMARY_PUBLISHABLE_KEY}`);
  assert.equal(headers['Content-Type'], 'application/json');

  // Custom user bearer token override (for authenticated staff sessions)
  const authedHeaders = supabaseConfig.getHeaders('custom.user.jwt');
  assert.equal(authedHeaders.apikey, PRIMARY_PUBLISHABLE_KEY);
  assert.equal(authedHeaders.Authorization, 'Bearer custom.user.jwt');
});

test('supabaseConfig generates correct Realtime Phoenix WebSocket URL', () => {
  const wsUrl = supabaseConfig.getRealtimeWebSocketUrl();
  assert.ok(wsUrl.startsWith('wss://nbmmfdiqywmkjaltezro.supabase.co/realtime/v1/websocket'));
  assert.ok(wsUrl.includes(`apikey=${encodeURIComponent(PRIMARY_PUBLISHABLE_KEY)}`));
  assert.ok(wsUrl.includes('vsn=1.0.0'));
});

test('normalizeProductImageUrl handles relative and absolute image paths', () => {
  assert.equal(
    supabaseConfig.normalizeProductImageUrl('https://example.com/photo.jpg'),
    'https://example.com/photo.jpg'
  );
  assert.equal(
    supabaseConfig.normalizeProductImageUrl('books/a4.jpg'),
    'https://nbmmfdiqywmkjaltezro.supabase.co/storage/v1/object/public/product-images/books/a4.jpg'
  );
  assert.equal(
    supabaseConfig.normalizeProductImageUrl('product-images/tape.png'),
    'https://nbmmfdiqywmkjaltezro.supabase.co/storage/v1/object/public/product-images/tape.png'
  );
  assert.equal(supabaseConfig.normalizeProductImageUrl(''), null);
  assert.equal(supabaseConfig.normalizeProductImageUrl(null), null);
  assert.equal(supabaseConfig.normalizeProductImageUrl('null'), null);
});
