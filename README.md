# MixTape 🎵

**Your multimedia playlist experience - where YOU have total control**

MixTape is a powerful Android application that puts complete playlist management control in your hands. Create, customize, and curate your perfect multimedia collections with unprecedented flexibility and features that adapt to your unique listening preferences.

## 🎯 Philosophy: Complete User Control

MixTape was built on the principle that **you should have absolute control over your media experience**. Unlike other platforms that limit your choices, MixTape empowers you with:

- **Full ownership** of your playlists and media files
- **Complete customization** of organization, tags, and metadata
- **Total control** over sharing, privacy, and collaboration
- **Unrestricted flexibility** in how you consume and manage your content

## ✨ Key Features

### 🎛️ **Advanced Playlist Management**
- **Create unlimited playlists** with custom names and descriptions
- **Mix audio and video** seamlessly in the same playlist
- **Deep customization** with personal tags and metadata
- **Smart organization** with multiple sorting and filtering options
- **Real-time editing** with staged changes and batch operations

### 🏷️ **Powerful Tagging System**
- **Custom tag creation** for your unique organizational needs
- **Global tag management** across all your content
- **Advanced filtering** by tags, duration, artist, album, and more
- **Automatic genre detection** from metadata
- **Tag-based discovery** to rediscover forgotten gems

### 🔄 **Flexible Sharing & Collaboration**
- **Share codes** - Generate unique 6-character codes for easy playlist sharing
- **Direct user sharing** - Share with specific users by email
- **Copy-based sharing** - Recipients get their own editable copy
- **Privacy control** - Choose what to share and with whom
- **Owner permissions** - Original creators maintain full control

### 🎵 **Unified Media Player**
- **Seamless audio/video playback** in a single interface
- **Background audio support** with persistent notifications
- **Real-time audio visualization** with frequency bars
- **Full video controls** with gesture support and orientation handling
- **Advanced playback modes** - repeat, autoplay, shuffle
- **Cross-session continuity** - resume where you left off

### 📱 **Professional User Experience**
- **Dark theme** optimized for extended use
- **Intuitive navigation** with slide-out sidebar
- **Smart filtering** with collapsible sections
- **Responsive design** that adapts to your device
- **Material Design 3** components for modern feel

## 🚀 Getting Started

### Prerequisites
- Android 7.0+ (API level 24)
- Google Play Services (for authentication)
- Storage permissions for media access
- Internet connection for cloud features

### Installation
1. Clone the repository
```bash
git clone https://github.com/yourusername/mixtape.git
cd mixtape
```

2. Open in Android Studio
3. Configure Firebase:
   - Add your `google-services.json` file to `app/`
   - Set up Firebase Authentication, Firestore, and Storage
4. Build and run

### First Time Setup
1. **Sign up** with email or Google account
2. **Grant permissions** for media access
3. **Create your first playlist**
4. **Add content** from your device
5. **Start organizing** with custom tags

## 🛠️ Technical Architecture

### Core Technologies
- **Kotlin** - Modern Android development
- **Firebase Suite** - Authentication, Firestore, Storage
- **Media3** - Advanced media playback and session management
- **Material Design 3** - Contemporary UI components
- **Coroutines** - Smooth asynchronous operations

### Key Components
- **UnifiedPlayerService** - Background media playback with cross-format support
- **FirebaseRepository** - Centralized cloud data management
- **Staged Changes System** - Batch operations for optimal performance
- **Dynamic UI Adapters** - Flexible content display and interaction
- **Custom Visualizer** - Real-time audio frequency visualization

## 🎨 User Control Features in Detail

### **Playlist Ownership**
- **Full deletion control** - Remove playlists and all associated content
- **Metadata editing** - Change titles, descriptions, and organization
- **Content curation** - Add/remove items with granular control
- **Tag management** - Create, edit, and delete custom organizational tags

### **Privacy & Sharing Control**
- **Selective sharing** - Choose exactly what to share and with whom
- **Permission levels** - Maintain owner privileges even when sharing
- **Revocable access** - Remove sharing permissions at any time
- **Copy-based collaboration** - Recipients get independent copies they can modify

### **Content Organization Control**
- **Multi-criteria sorting** - By date, title, artist, album, or custom order
- **Advanced filtering** - Combine multiple filters for precise results
- **Custom tagging** - Create your own organizational system
- **Flexible views** - Adapt the interface to your preferences

### **Playback Control**
- **Queue management** - Full control over play order and selection
- **Playback modes** - Repeat, shuffle, autoplay with your preferences
- **Cross-device continuity** - Resume exactly where you left off
- **Background control** - Full functionality even when app is backgrounded

## 📁 Project Structure

```
app/
├── src/main/
│   ├── java/com/example/mixtape/
│   │   ├── model/              # Data models (Playlist, Song, Video, User)
│   │   ├── adapters/           # RecyclerView adapters for dynamic UI
│   │   ├── service/            # Background services (UnifiedPlayerService)
│   │   ├── ui/                 # Custom UI components and dialogs
│   │   ├── utilities/          # Helper classes and Firebase integration
│   │   ├── LoginActivity.kt    # Authentication and registration
│   │   ├── PlaylistSelectionActivity.kt  # Main playlist dashboard
│   │   ├── PlaylistActivity.kt # Detailed playlist view and management
│   │   └── UnifiedPlayerActivity.kt      # Audio/video player interface
│   └── res/                    # Resources (layouts, drawables, styles)
├── build.gradle               # App dependencies and configuration
└── google-services.json      # Firebase configuration (not included)
```

## 🔐 Data Privacy & Control

MixTape gives you complete control over your data:

- **Local processing** - Metadata extraction happens on your device
- **Encrypted storage** - All cloud data is securely encrypted
- **User ownership** - You own all content you upload
- **Export capability** - Full data portability (coming soon)
- **Deletion guarantee** - Complete removal when you delete content

## 🙏 Acknowledgments

- **Firebase** - Robust backend infrastructure
- **Material Design** - Beautiful and consistent UI components
- **Android Media3** - Professional media playback capabilities
- **Open Source Community** - Inspiration and foundational libraries

---

## 💡 Why MixTape?

In a world where streaming services dictate what you can listen to and how you can organize it, MixTape returns control to where it belongs - **with you**. 

✅ **Your content, your way**  
✅ **Your organization, your rules**  
✅ **Your sharing, your choice**  
✅ **Your experience, your control**

**Download MixTape and take back control of your music experience.**

---

*Built with ❤️ for users who demand complete control over their digital media experience*
