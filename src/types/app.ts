export type NavTab = 'home' | 'scan' | 'catalog' | 'sales' | 'stock' | 'money' | 'orders' | 'activity' | 'workspace' | 'brand' | 'settings' | 'android-hub' | 'backend';

export type ThemePreference = 'light' | 'dark' | 'system';

export type GlassIntensity = 'subtle' | 'balanced' | 'frosted';

export type DensityMode = 'comfortable' | 'compact';

export interface WorkspaceItem {
  id: string;
  title: string;
  category: 'Specification' | 'Module' | 'Note';
  completed: boolean;
  updatedAt: string;
}

export interface AppPreferences {
  glassIntensity: GlassIntensity;
  reducedMotion: boolean;
  density: DensityMode;
}
