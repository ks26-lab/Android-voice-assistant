import './styles/global.css';
import './styles/layout.css';
import { App } from './App.js';

const root = document.getElementById('app');
if (root) {
  root.appendChild(App());
}
