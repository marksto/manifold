package manifold.api.sourceprod;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import manifold.api.fs.IFile;
import manifold.api.fs.cache.ModulePathCache;
import manifold.api.service.BaseService;
import manifold.api.host.AbstractTypeSystemListener;
import manifold.api.host.IModule;
import manifold.api.host.ITypeLoader;
import manifold.api.host.RefreshKind;
import manifold.api.host.RefreshRequest;
import manifold.internal.host.ManifoldHost;
import manifold.util.GosuClassUtil;
import manifold.util.StreamUtil;
import manifold.util.cache.FqnCache;
import manifold.util.concurrent.LocklessLazyVar;

/**
 * A base class for a source producer that is based on a resource file of a specific extension.
 *
 * @param <M> The model you derive backing production of source code.
 */
public abstract class ResourceFileSourceProducer<M extends ResourceFileSourceProducer.IModel> extends BaseService implements ISourceProducer
{
  private ITypeLoader _typeLoader;
  private Set<String> _extensions;
  private LocklessLazyVar<FqnCache<LocklessLazyVar<M>>> _fqnToModel;
  private  String _typeFactoryFqn;
  private  BiFunction<String, IFile, M> _modelMapper;
  @SuppressWarnings("all")
  private CacheClearer _cacheClearer;

  @Override
  public void init( ITypeLoader tl )
  {
    _typeLoader = tl;
  }

  /**
   * @param typeLoader The typeloader passed into the ISourceProvider implementation constructor
   * @param extensions The extension of the resource file this source producer handles
   * @param modelMapper A function to provide a model given a qualified name and resource file
   */
  protected void init( ITypeLoader typeLoader, Set<String> extensions, BiFunction<String, IFile, M> modelMapper )
  {
    init( typeLoader, extensions, modelMapper, null );
  }
  /**
   * @param typeLoader The typeloader passed into the ISourceProvider implementation constructor
   * @param extensions The extension of the resource file this source producer handles
   * @param modelMapper A function to provide a model given a qualified name and resource file
   * @param typeFactoryFqn For Gosu Lab.  Optional.
   */
  protected void init( ITypeLoader typeLoader, Set<String> extensions, BiFunction<String, IFile, M> modelMapper, String typeFactoryFqn )
  {
    _typeLoader = typeLoader;
    _extensions = extensions;
    _typeFactoryFqn = typeFactoryFqn;
    _modelMapper = modelMapper;
    _fqnToModel = LocklessLazyVar.make( () -> {
      FqnCache<LocklessLazyVar<M>> cache = new FqnCache<>();
      for( String ext: _extensions )
      {
        FqnCache<IFile> fileCache = ModulePathCache.instance().get( getModule() ).getExtensionCache( ext );
        fileCache.getFqns().forEach( fqn -> {
          IFile file = fileCache.get( fqn );
          if( file != null && handlesFile( file ) )
          {
            String aliasFqn = aliasFqn( fqn, file );
            if( aliasFqn != null )
            {
              cache.add( aliasFqn, LocklessLazyVar.make( () -> _modelMapper.apply( aliasFqn, file ) ) );
              for( String addFqn : getAdditionalTypes( aliasFqn, file ) )
              {
                cache.add( addFqn, LocklessLazyVar.make( () -> getModel( aliasFqn ) ) ); // use same model as base fqn
              }
            }
          }
        } );
      }
      Map<String, LocklessLazyVar<M>> peripheralTypes = getPeripheralTypes();
      if( peripheralTypes != null )
      {
        cache.addAll( peripheralTypes );
      }
      return cache;
    } );
    ManifoldHost.addTypeLoaderListenerAsWeakRef( getModule(), _cacheClearer = new CacheClearer() );
  }

  /**
   * A map of name-to-model peripheral to the main map of name-to-model,
   * possibly including types that are not file-based. Optional.
   */
  protected Map<String, LocklessLazyVar<M>> getPeripheralTypes()
  {
    return Collections.emptyMap();
  }

  /**
   * Opportunity for subclass to alias the fqn produced from the file name and package.
   */
  protected String aliasFqn( String fqn, IFile file )
  {
    return fqn;
  }

  /**
   * Additional types that map to the same file as the base fqn.
   * These can be supporting types, interfaces, what have you.
   */
  protected Set<String> getAdditionalTypes( String fqn, IFile file )
  {
    return Collections.emptySet();
  }

  /**
   * @param topLevelFqn Qualified name of top-level type
   * @param relativeInner Top-level relative name of inner class
   * @return true if relativeInner is an inner class of topLevel
   */
  protected abstract boolean isInnerType( String topLevelFqn, String relativeInner );

  /**
   * Generate Source code for the named model.
   * @param topLevelFqn The qualified name of the top-level type to produce.
   * @param existing The source produced from other producers so far; if not empty, this producer must not be a Primary producer.
   *@param model The model your source code provider uses to generate the source.  @return The source code for the specified top-level type.
   */
  protected abstract String produce( String topLevelFqn, String existing, M model, DiagnosticListener<JavaFileObject> errorHandler );

  protected M getModel( String topLevel )
  {
    LocklessLazyVar<M> lazyModel = _fqnToModel.get().get( topLevel );
    return lazyModel == null ? null : lazyModel.get();
  }

  @Override
  public boolean handlesFile( IFile file )
  {
    return _extensions.stream().anyMatch( ext -> ext.equalsIgnoreCase( file.getExtension() ) );
  }

  @Override
  public String[] getTypesForFile( IFile file )
  {
    if( !handlesFile( file ) )
    {
      return new String[0];
    }

    Set<String> fqns = ModulePathCache.instance().get( getModule() ).getFqnForFile( file );
    Set<String> aliasedFqns = new HashSet<>();
    if( fqns != null )
    {
      for( String fqn: fqns )
      {
        fqn = aliasFqn( fqn, file );
        if( fqn != null )
        {
          aliasedFqns.add( fqn );
        }
      }
    }
    return aliasedFqns.toArray( new String[aliasedFqns.size()] );
  }

  public IModule getModule()
  {
    return _typeLoader.getModule();
  }

  @Override
  public RefreshKind refreshedFile( IFile file, String[] types, RefreshKind kind )
  {
    _fqnToModel.clear();
    return kind;
  }

  @Override
  public ITypeLoader getTypeLoader()
  {
    return _typeLoader;
  }

  @Override
  public Set<String> getExtensions()
  {
    return _extensions;
  }

  @Override
  public boolean isType( String fqn )
  {
    String topLevel = findTopLevelFqn( fqn );
    if( topLevel == null )
    {
      return false;
    }

    if( topLevel.equals( fqn ) )
    {
      return true;
    }

    return isInnerType( topLevel, fqn.substring( topLevel.length()+1 ) );
  }

  @Override
  public boolean isPackage( String pkg )
  {
    return !getTypeNames( pkg ).isEmpty();
  }

  /**
   * This method avoids initializing all the files
   */
  protected String findTopLevelFqn( String fqn )
  {
    while( true )
    {
      LocklessLazyVar<M> lazyModel = _fqnToModel.get().get( fqn );
      if( lazyModel != null )
      {
        return fqn;
      }
      int iDot = fqn.lastIndexOf( '.' );
      if( iDot <= 0 )
      {
        return null;
      }
      fqn = fqn.substring( 0, iDot );
    }
  }

  @Override
  public boolean isTopLevelType( String fqn )
  {
    return _fqnToModel.get().get( fqn ) != null;
  }

  @Override
  public String getPackage( String fqn )
  {
    String topLevel = findTopLevelFqn( fqn );
    return GosuClassUtil.getPackage( topLevel );
  }

  @Override
  public String produce( String fqn, String existing, DiagnosticListener<JavaFileObject> errorHandler )
  {
    String topLevel = findTopLevelFqn( fqn );
    LocklessLazyVar<M> lazyModel = _fqnToModel.get().get( topLevel );

    String source = produce( topLevel, existing, lazyModel.get(), errorHandler );

    // Now remove the model since we don't need it anymore
    lazyModel.clear();

    return source;
  }

  @Override
  public Collection<String> getAllTypeNames()
  {
    return _fqnToModel.get().getFqns();
  }

  @Override
  public Collection<TypeName> getTypeNames( String namespace )
  {
    Set<TypeName> collect = getAllTypeNames().stream().filter( fqn -> GosuClassUtil.getPackage( fqn ).equals( namespace ) ).map( fqn -> new TypeName( fqn, _typeLoader, TypeName.Kind.TYPE, TypeName.Visibility.PUBLIC ) ).collect( Collectors.toSet() );
    return collect;
  }

  @Override
  public List<IFile> findFilesForType( String fqn )
  {
    String topLevel = findTopLevelFqn( fqn );
    if( topLevel == null )
    {
      return Collections.emptyList();
    }

    M model = getModel( topLevel );
    return model != null ? model.getFiles() : Collections.emptyList();
  }

  @Override
  public void clear()
  {
    _fqnToModel.clear();
  }

  @Override
  public <T> List<T> getInterface( Class<T> apiInterface )
  {
    if( _typeFactoryFqn == null || _typeFactoryFqn.isEmpty() )
    {
      return super.getInterface( apiInterface );
    }

    if( _fqnToModel != null && apiInterface.getName().equals( "editor.plugin.typeloader.ITypeFactory" ) )
    {
      try
      {
        //noinspection unchecked
        return Collections.singletonList( (T)Class.forName( _typeFactoryFqn ).newInstance() );
      }
      catch( Exception e )
      {
        throw new RuntimeException( e );
      }
    }
    return super.getInterface( apiInterface );
  }

  public static String getContent( IFile file )
  {
    if( file != null )
    {
      try( InputStream inputStream = file.openInputStream() )
      {
        return StreamUtil.getContent( new InputStreamReader( inputStream ) ).replace( "\r\n", "\n" );
      }
      catch( Exception e )
      {
        throw new RuntimeException( e );
      }
    }
    return null;
  }

  private class CacheClearer extends AbstractTypeSystemListener
  {
    @Override
    public void refreshed()
    {
      clear();
    }

    @Override
    public void refreshedTypes( RefreshRequest request )
    {
      IModule refreshModule = request.module;
      if( refreshModule != null && refreshModule != getModule() )
      {
        return;
      }

      if( request.file == null || !handlesFile( request.file ) )
      {
        return;
      }

      switch( request.kind )
      {
        case MODIFICATION:
          Arrays.stream( request.types ).forEach(
            fqn -> {
              LocklessLazyVar<M> lazyModel = _fqnToModel.get().get( fqn );
              if( lazyModel != null )
              {
                lazyModel.clear();
              }
              else if( request.file != null )
              {
                String aliasFqn = aliasFqn( fqn, request.file );
                if( aliasFqn != null )
                {
                  _fqnToModel.get().add( aliasFqn, LocklessLazyVar.make( () -> _modelMapper.apply( aliasFqn, request.file ) ) );
                }
              }
            } );
          break;

        case CREATION:
        {
          Arrays.stream( request.types ).forEach(
            fqn -> {
              if( request.file != null )
              {
                String aliasFqn = aliasFqn( fqn, request.file );
                if( aliasFqn != null )
                {
                  _fqnToModel.get().add( aliasFqn, LocklessLazyVar.make( () -> _modelMapper.apply( aliasFqn, request.file ) ) );
                }
              }
            } );
          break;
        }

        case DELETION:
        {
          Arrays.stream( request.types ).forEach(
            fqn -> _fqnToModel.get().remove( fqn ) );
          break;
        }
      }
    }
  }

  public interface IModel
  {
    String getFqn();
    List<IFile> getFiles();
  }
}
